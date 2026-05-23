#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <time.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <pthread.h>
#include <stdint.h>
#include <android/log.h>

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

/*
 * Timing hook detector. Frida's Interceptor adds a trampoline + JS callback
 * on every hooked call, which is enormous relative to a bare syscall like
 * getpid(). Rather than picking a fixed threshold (fragile across devices),
 * we compare libc getpid() against raw syscall(SYS_getpid) in identical
 * loops. Both reach the kernel and do the same trivial work, so on an
 * unhooked device the libc loop is within ~2x of the raw loop. A hooked
 * libc loop is typically 5-50x slower.
 */
static long ns_diff(const struct timespec *a, const struct timespec *b) {
    return (b->tv_sec - a->tv_sec) * 1000000000L + (b->tv_nsec - a->tv_nsec);
}

static long time_libc_getpid(int iters) {
    struct timespec t1, t2;
    volatile pid_t sink = 0;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    for (int i = 0; i < iters; i++) sink = getpid();
    clock_gettime(CLOCK_MONOTONIC, &t2);
    (void)sink;
    return ns_diff(&t1, &t2);
}

static long time_raw_getpid(int iters) {
    struct timespec t1, t2;
    volatile long sink = 0;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    for (int i = 0; i < iters; i++) sink = syscall(SYS_getpid);
    clock_gettime(CLOCK_MONOTONIC, &t2);
    (void)sink;
    return ns_diff(&t1, &t2);
}

static int check_timing_hook(void) {
    const int iters  = 5000;
    const int trials = 3;
    const long ratio_threshold = 4;  /* libc must not exceed 4x raw */

    /* Warm caches / branch predictors so the first measured trial isn't
     * artificially slow. */
    (void)time_libc_getpid(200);
    (void)time_raw_getpid(200);

    /* Take the min over a few trials to suppress scheduler noise — a
     * preempted run only inflates the measurement, never deflates it. */
    long libc_best = -1, raw_best = -1;
    for (int t = 0; t < trials; t++) {
        long l = time_libc_getpid(iters);
        long r = time_raw_getpid(iters);
        if (libc_best < 0 || l < libc_best) libc_best = l;
        if (raw_best  < 0 || r < raw_best)  raw_best  = r;
    }

    if (raw_best <= 0) return 0;  /* clock skew or measurement failure */
    return libc_best > raw_best * ratio_threshold;
}

/*
 * Native root detection. We stat the canonical su / root-manager binaries
 * and the control directories of the common rooting frameworks (Magisk,
 * KernelSU, APatch). This is deliberately conservative: it keys on superuser
 * *tooling*, not on "non-stock ROM" signals like ro.build.tags=test-keys, so
 * users on signed custom ROMs (e.g. GrapheneOS) that are not actually rooted
 * are NOT flagged. Limitations: an unprivileged process cannot search some
 * paths under /data/adb (mode 700), and modern Magisk DenyList can hide su
 * from these probes — Play Integrity (verified server-side) remains the
 * authoritative root signal. This is a cheap, hard-to-hook first line.
 */
static int check_root(void) {
    static const char *const root_paths[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su-backup",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU",
        "/system/xbin/daemonsu",
        "/system/bin/magisk",
        "/system/xbin/magisk",
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/data/adb/magisk.db",
        "/data/adb/modules",
        "/data/adb/ksu",   /* KernelSU */
        "/data/adb/ap",    /* APatch   */
    };
    for (size_t i = 0; i < sizeof(root_paths) / sizeof(root_paths[0]); i++) {
        if (access(root_paths[i], F_OK) == 0) return 1;
    }
    return 0;
}

/*
 * Immediate, silent process termination. SIGKILL cannot be caught, blocked,
 * or ignored; _exit() is a belt-and-suspenders fallback. We deliberately
 * avoid exit()/abort() so that no atexit handlers, static destructors, or
 * "app keeps stopping" crash dialog run — the process simply disappears.
 */
__attribute__((unused)) static void terminate_now(void) {
    kill(getpid(), SIGKILL);
    _exit(0);
}

#ifdef PROOFMODE_LETHAL_INTEGRITY
/*
 * Self-ptrace anti-debug watchdog (release builds only).
 *
 * On Linux/Android a task can have at most one tracer. We fork a child that
 * immediately PTRACE_ATTACHes back to us: while it holds that slot, no other
 * debugger — frida-server's ptrace injector, gdb, lldb — can attach. A monitor
 * thread in the parent waits on the child and kills the process if the child
 * ever dies (i.e. someone tore the watchdog off). This complements the
 * maps/thread checks, which cover the LD_PRELOAD / repackaged-gadget path that
 * does not use ptrace.
 *
 * Adapted from a public technique (CC BY-SA 4.0, stackoverflow.com/a/59467654)
 * and hardened for production:
 *   - the forked child uses only async-signal-safe calls (no fopen/snprintf),
 *     since it runs after fork() from the multithreaded JVM;
 *   - real signals are forwarded on PTRACE_CONT so the parent's own handlers
 *     still fire — critically ART's implicit null-check SIGSEGV/SIGBUS;
 *   - a kernel-policy attach failure with no debugger present (e.g. Yama
 *     ptrace_scope=1) degrades gracefully instead of killing a real user's app.
 *
 * Caveat: while we hold our own tracer slot, the OS crash handler (debuggerd)
 * cannot attach to produce a full native tombstone — native crash reports may
 * be degraded on release builds.
 */
#define WATCHDOG_EXIT_BENIGN   13   /* ptrace policy-blocked, no debugger */
#define WATCHDOG_EXIT_DEBUGGER 42   /* a debugger was already attached    */

static volatile pid_t g_watchdog_child = -1;

/*
 * Async-signal-safe check for whether `pid` has a tracer, by scanning
 * /proc/<pid>/status for a nonzero TracerPid. Runs in the forked child, where
 * libc stdio/heap (fopen, snprintf) are unsafe, so we hand-build the path and
 * use raw open/read. strstr is pure (no locks/heap) and safe here.
 */
static int pid_is_traced(pid_t pid) {
    char path[64];
    int i = 0;
    for (const char *p = "/proc/"; *p; p++) path[i++] = *p;
    char digits[16];
    int n = 0;
    unsigned int v = (unsigned int)pid;
    if (v == 0) digits[n++] = '0';
    while (v > 0) { digits[n++] = (char)('0' + (v % 10)); v /= 10; }
    while (n > 0) path[i++] = digits[--n];
    for (const char *p = "/status"; *p; p++) path[i++] = *p;
    path[i] = '\0';

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    char buf[1024];
    ssize_t got = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (got <= 0) return 0;
    buf[got] = '\0';

    const char *t = strstr(buf, "TracerPid:");
    if (!t) return 0;
    t += 10;
    while (*t == ' ' || *t == '\t') t++;
    return (*t >= '1' && *t <= '9');
}

/* The watchdog child. Never returns — always _exit()s with a status the
 * parent's monitor thread interprets. */
static void watchdog_child(pid_t ppid) {
    if (ptrace(PTRACE_ATTACH, ppid, NULL, NULL) != 0) {
        /* Could not attach. If the parent is already traced, that is exactly
         * the attack we defend against -> signal a kill. Otherwise the kernel
         * policy forbade the self-attach with no debugger present -> degrade
         * gracefully so we never kill a legitimate user's app. */
        if (pid_is_traced(ppid)) _exit(WATCHDOG_EXIT_DEBUGGER);
        _exit(WATCHDOG_EXIT_BENIGN);
    }

    int status;
    waitpid(ppid, &status, 0);            /* consume the initial attach SIGSTOP */
    ptrace(PTRACE_CONT, ppid, NULL, NULL);

    /* Hold the tracer slot for the life of the parent. */
    while (waitpid(ppid, &status, 0) == ppid) {
        if (WIFEXITED(status) || WIFSIGNALED(status)) _exit(0);  /* parent gone */
        if (WIFSTOPPED(status)) {
            int sig = WSTOPSIG(status);
            /* Forward real signals so the parent's handlers run (ART relies on
             * SIGSEGV/SIGBUS for implicit null checks); swallow the trace-
             * control signals so we don't re-stop or loop the parent. */
            if (sig == SIGSTOP || sig == SIGTRAP) sig = 0;
            ptrace(PTRACE_CONT, ppid, NULL, (void *)(intptr_t)sig);
        }
    }
    _exit(0);
}

/* Parent-side monitor: kills the app if the watchdog dies abnormally. */
static void *watchdog_monitor(void *arg) {
    (void)arg;
    if (g_watchdog_child <= 0) return NULL;
    int status;
    waitpid(g_watchdog_child, &status, 0);
    /* The watchdog should outlive everything but the parent itself. A benign
     * exit means ptrace was merely policy-blocked (no debugger) -> leave the
     * app alone. Any other death means a debugger was detected or the watchdog
     * was forcibly removed -> terminate. */
    if (WIFEXITED(status) && WEXITSTATUS(status) == WATCHDOG_EXIT_BENIGN)
        return NULL;
    terminate_now();
    return NULL;
}

/* Fork the watchdog and start its monitor. Safe to call once at load. */
static void arm_anti_debug(void) {
    pid_t pid = fork();
    if (pid < 0) return;          /* fork failed: skip rather than misbehave */
    if (pid == 0) {
        watchdog_child(getppid());
        _exit(0);                 /* unreachable */
    }
    g_watchdog_child = pid;
    pthread_t t;
    if (pthread_create(&t, NULL, watchdog_monitor, NULL) == 0)
        pthread_detach(t);
}
#endif /* PROOFMODE_LETHAL_INTEGRITY */

/*
 * Load-time tripwire. Runs the deterministic, low-false-positive detectors:
 * root plus the file/thread/port/map/syscall Frida checks. The timing probe
 * is intentionally excluded here — it is device- and load-dependent and thus
 * the wrong thing to make lethal at startup. The full set (timing included)
 * still runs on demand via nativeIsEnvironmentCompromised() at signing time.
 */
static int integrity_tripwire(void) {
    return check_root()           ||
           check_frida_maps()     ||
           check_anon_exec_maps() ||
           check_frida_port()     ||
           check_frida_threads()  ||
           check_syscall_hook();
}

/*
 * ELF library constructor. Entries in .init_array run during dlopen(), as the
 * dynamic linker finishes mapping libdintegrity — before JNI_OnLoad and before
 * whoever triggered the load (our ProofModeApp companion-object loader, or the
 * framework, were this lib ever named by a NativeActivity) regains control.
 * This is the earliest point our own code executes in the process, so it is
 * where the lethal tripwire belongs. JNI_OnLoad re-runs the same tripwire as a
 * cheap second checkpoint in case .init_array execution is tampered with.
 *
 * We deliberately do NOT fork the anti-debug watchdog here: forking under the
 * linker lock during dlopen is unsafe. That stays in JNI_OnLoad, which the JVM
 * invokes at a settled point after the load completes.
 */
__attribute__((constructor))
static void dintegrity_ctor(void) {
    if (integrity_tripwire()) {
#ifdef PROOFMODE_LETHAL_INTEGRITY
        terminate_now();
#else
        __android_log_print(ANDROID_LOG_WARN, "dintegrity",
            "integrity tripwire fired at ctor (non-lethal: debug build)");
#endif
    }
}

/*
 * JNI_OnLoad runs inside System.loadLibrary("dintegrity"), before the JVM
 * binds any native method and before any app startup code that follows the
 * load. In release builds (PROOFMODE_LETHAL_INTEGRITY) a tripped detector
 * kills the process here, in native code, leaving no JVM-visible verdict for
 * Frida to hook and no branch to patch. Debug builds only log, so local
 * development, CI, and emulators (which often look "rooted") stay usable.
 */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    if (integrity_tripwire()) {
#ifdef PROOFMODE_LETHAL_INTEGRITY
        terminate_now();
#else
        __android_log_print(ANDROID_LOG_WARN, "dintegrity",
            "integrity tripwire fired at load (non-lethal: debug build)");
#endif
    }
#ifdef PROOFMODE_LETHAL_INTEGRITY
    /* Arm the self-ptrace watchdog: occupy our own tracer slot so no debugger
     * (frida-server inject / gdb / lldb) can attach later, and kill the process
     * if one is detected or the watchdog is torn off. Release builds only, so
     * native debugging under Android Studio still works in debug builds. */
    arm_anti_debug();
#endif
    return JNI_VERSION_1_6;
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
    if (check_timing_hook())    return JNI_TRUE;
    return JNI_FALSE;
}
