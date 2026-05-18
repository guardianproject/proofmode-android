#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>

static int check_frida_maps(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[1024];
    int hit = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "frida")     ||
            strstr(line, "gum-js")    ||
            strstr(line, "gumjs")     ||
            strstr(line, "linjector") ||
            strstr(line, "re.frida")) {
            hit = 1;
            break;
        }
    }
    fclose(f);
    return hit;
}

/*
 * Anonymous writable+executable pages are a red flag: legitimate code is
 * always file-backed, and modern ART JIT uses dual-mapping (separate rw- and
 * r-x views), so a true rwxp region with no file path strongly suggests
 * injected code (Frida's gadget/agent or a similar injector).
 *
 * Line format: addr-addr perms offset dev inode [pathname]
 * Anonymous = inode 0 AND no pathname after the inode field.
 */
static int check_anon_exec_maps(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[1024];
    int hit = 0;
    while (fgets(line, sizeof(line), f)) {
        unsigned long start, end, offset, inode;
        char perms[8];
        unsigned int dev_major, dev_minor;
        int consumed = 0;
        if (sscanf(line, "%lx-%lx %7s %lx %x:%x %lu %n",
                   &start, &end, perms, &offset,
                   &dev_major, &dev_minor, &inode, &consumed) < 7) {
            continue;
        }
        if (strchr(perms, 'w') == NULL || strchr(perms, 'x') == NULL) continue;
        if (inode != 0) continue;

        const char *rest = (consumed > 0) ? line + consumed : "";
        while (*rest == ' ' || *rest == '\t') rest++;
        /* Truly unlabeled anonymous rwx region — no pathname at all. */
        if (*rest == '\0' || *rest == '\n') {
            hit = 1;
            break;
        }
    }
    fclose(f);
    return hit;
}

/*
 * /proc/net/tcp and /proc/net/tcp6 layout:
 *   sl  local_address rem_address   st tx_queue:rx_queue ...
 *   0:  0100007F:699A 00000000:0000 0A ...
 * local_address is HEX:HEX (IPv4 = 8 hex chars, IPv6 = 32 hex chars).
 * st == 0A means LISTEN.
 */
static int file_has_listening_port(const char *path, unsigned int port) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[512];
    if (!fgets(line, sizeof(line), f)) {  /* skip header */
        fclose(f);
        return 0;
    }
    int hit = 0;
    while (fgets(line, sizeof(line), f)) {
        char local[64], remote[64], state[4];
        if (sscanf(line, " %*d: %63[0-9A-Fa-f:] %63[0-9A-Fa-f:] %3[0-9A-Fa-f]",
                   local, remote, state) != 3) {
            continue;
        }
        char *colon = strrchr(local, ':');
        if (!colon) continue;
        unsigned int p = (unsigned int)strtoul(colon + 1, NULL, 16);
        if (p == port && strcmp(state, "0A") == 0) {
            hit = 1;
            break;
        }
    }
    fclose(f);
    return hit;
}

static int check_frida_port(void) {
    /* 27042 = 0x699A (frida-server),
     * 27043 = 0x699B (frida-build-server / fallback) */
    const unsigned int ports[] = {0x699A, 0x699B};
    for (size_t i = 0; i < sizeof(ports) / sizeof(ports[0]); i++) {
        if (file_has_listening_port("/proc/net/tcp",  ports[i])) return 1;
        if (file_has_listening_port("/proc/net/tcp6", ports[i])) return 1;
    }
    return 0;
}

static int comm_looks_like_frida(const char *name) {
    return strstr(name, "gmain")      != NULL ||
           strstr(name, "gum-js")     != NULL ||
           strstr(name, "frida")      != NULL ||
           strstr(name, "pool-frida") != NULL ||
           strstr(name, "gdbus")      != NULL;
}

static int check_frida_threads(void) {
    /* Frida injects helper threads with recognizable comm names. */
    DIR *d = opendir("/proc/self/task");
    if (!d) return 0;
    struct dirent *entry;
    int hit = 0;
    char path[256];
    char name[64];
    while ((entry = readdir(d)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);
        FILE *cf = fopen(path, "r");
        if (!cf) continue;
        if (fgets(name, sizeof(name), cf) && comm_looks_like_frida(name)) {
            fclose(cf);
            hit = 1;
            break;
        }
        fclose(cf);
    }
    closedir(d);
    return hit;
}

/*
 * Inline syscall integrity: compare libc's open() against a raw syscall.
 * If libc has been hooked (e.g. Frida's Interceptor.attach on open/openat),
 * the libc wrapper and the kernel will disagree on success. /proc/self/maps
 * is always present and readable by the process, so the only way the two
 * results diverge is interception.
 */
static int check_syscall_hook(void) {
    const char *path = "/proc/self/maps";

    int fd_libc = open(path, O_RDONLY);
    int fd_raw  = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY, 0);

    int libc_ok = (fd_libc >= 0);
    int raw_ok  = (fd_raw  >= 0);

    if (fd_libc >= 0) close(fd_libc);
    if (fd_raw  >= 0) close(fd_raw);

    return libc_ok != raw_ok;
}

JNIEXPORT jboolean JNICALL
Java_org_witness_proofmode_c2pa_DeviceIntegritySupport_nativeIsEnvironmentCompromised(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (check_frida_maps())     return JNI_TRUE;
    if (check_anon_exec_maps()) return JNI_TRUE;
    if (check_frida_port())     return JNI_TRUE;
    if (check_frida_threads())  return JNI_TRUE;
    if (check_syscall_hook())   return JNI_TRUE;
    return JNI_FALSE;
}
