# Frida / Instrumentation Hardening

This document describes the native anti-instrumentation checks added to
`android-libproofmode` to raise the cost of attaching dynamic instrumentation
frameworks (primarily [Frida](https://frida.re/)) to ProofMode at runtime.

The goal is **not** to make instrumentation impossible — a determined attacker
with a custom Frida build, kernel-level hooking, or a patched APK can still
defeat any in-process check. The goal is to make casual / off-the-shelf
attacks visibly fail, and to record evidence in the capture pipeline that the
device state was untrustworthy at the moment a piece of media was signed.

## Where the checks live

| Layer  | File                                                                                                                | Purpose                                                                                       |
| ------ | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| Kotlin | `android-libproofmode/src/main/java/org/witness/proofmode/c2pa/DeviceIntegritySupport.kt`                           | Aggregates threat signals, exposes `detectThreats(Context)` and `isEnvironmentCompromised()`. |
| Native | `android-libproofmode/src/main/cpp/native-lib.c`                                                                    | Performs the hard-to-hook checks below; built as `libdintegrity.so`.                          |
| Build  | `android-libproofmode/build.gradle`, `android-libproofmode/src/main/cpp/CMakeLists.txt`                             | Adds CMake / NDK external build; ships `.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`. |
| R8     | `android-libproofmode/proguard-rules.pro`                                                                           | `-keepclasseswithmembernames` rule so the JNI symbol name is not renamed.                     |

The Kotlin layer calls `System.loadLibrary("dintegrity")` inside a
`runCatching` guard so that a missing or blocked `.so` degrades gracefully
instead of crashing app startup. If the native lib is unavailable, the JVM
fallbacks in `isFridaPresent()` still run.

## The checks

`DeviceIntegritySupport.nativeIsEnvironmentCompromised()` returns `true` as
soon as **any** of the following fires. They are ordered cheapest-first.

### 1. `/proc/self/maps` string scan

`check_frida_maps()` reads `/proc/self/maps` line-by-line via `fopen` /
`fgets` and looks for telltale substrings:

- `frida`, `re.frida` — Frida agent and gadget binaries
- `gum-js`, `gumjs` — Frida's GumJS JavaScript runtime
- `linjector` — Linux process-injection helper Frida ships with

Defeats: default Frida builds, frida-gadget injected into the APK.
Bypassed by: a Frida build with renamed symbols, or an attacker who unmaps
the agent after injection.

### 2. Anonymous executable region scan

`check_anon_exec_maps()` parses each `/proc/self/maps` line with `sscanf` and
flags any region that is **all of**:

- Writable **and** executable (`perms` contains both `w` and `x`)
- `inode == 0` (no file backing)
- No pathname after the inode field (truly unlabeled — not `[stack]`,
  `[heap]`, `[anon:...]`, etc.)

Legitimate code is always file-backed, and modern ART JIT uses split rw- /
r-x dual-mapping rather than a single `rwxp` region — so an unlabeled rwxp
anonymous mapping strongly suggests injected code.

**False-positive risk:** some third-party scripting / VM dependencies
(LuaJIT, certain AV SDKs) deliberately allocate rwxp anonymous code
buffers. ProofMode does not currently pull in any such dependency; if one
is added, this check will need to count regions or whitelist labels rather
than fire on a single hit.

### 3. Frida control-port scan

`check_frida_port()` parses `/proc/net/tcp` and `/proc/net/tcp6` and flags
any LISTEN socket (state `0A`) bound to:

- `27042` (`0x699A`) — frida-server default control port
- `27043` (`0x699B`) — frida-build-server / common fallback port

This is a system-wide signal, not just in-process: if `frida-server` is
running on the device under any process, we see it.

Bypassed by: starting frida-server on a non-default port, or routing it
through `adb forward` so the listener lives on the host machine. We rely on
the next check to catch the latter case.

### 4. Helper-thread name scan

`check_frida_threads()` walks `/proc/self/task/*` with `opendir` /
`readdir` and reads each thread's `comm` file. It flags names containing:

- `gmain`, `gdbus` — GLib main loop / D-Bus threads Frida ships with
- `gum-js` — GumJS VM thread
- `frida`, `pool-frida` — explicit Frida thread names

This catches Frida-gadget mode (where there is no `frida-server` to
port-scan) because the injected agent still spawns these worker threads
inside the target process.

### 5. Inline syscall integrity

`check_syscall_hook()` is one of the two hardest to defeat. It opens
`/proc/self/maps` two ways:

```c
int fd_libc = open(path, O_RDONLY);
int fd_raw  = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY, 0);
```

…and flags a hook if the two results disagree on success. Frida's typical
`Interceptor.attach` hooks the libc PLT trampoline for `open` / `openat`,
so the libc call returns a hooked result while the raw `syscall()`
instruction reaches the kernel unmodified.

Bypassed by: a hook that intercepts at the SVC / syscall-instruction level
(seccomp-bpf, ptrace, kernel module). Those approaches are much higher
effort and usually leave their own fingerprints.

### 6. Timing-side-channel detector

`check_timing_hook()` exploits the fact that Frida's `Interceptor` adds a
trampoline + JavaScript callback to every hooked call. That overhead is
enormous (hundreds of nanoseconds to several microseconds) relative to a
trivial syscall like `getpid()`. We measure it with a comparative loop:

```c
long t_libc = time_libc_getpid(5000);              // for (...) getpid();
long t_raw  = time_raw_getpid(5000);               // for (...) syscall(SYS_getpid);
return t_libc > t_raw * 4;                         // libc must not exceed 4x raw
```

Both loops perform the same kernel work, so on an unhooked device the
libc version is within ~2x of the raw version (the small delta is the libc
prologue). A hooked libc `getpid()` typically runs 5–50x slower than the
raw syscall, easily clearing the 4x threshold.

Why a *ratio* and not a fixed nanosecond threshold:

- A fixed `THRESHOLD_NS` calibrated on a Pixel will false-positive on a
  budget device and miss hooks on a flagship.
- A fixed threshold needs re-tuning whenever the NDK changes the libc
  wrapper, the kernel changes syscall fast-path latency, or someone runs
  on an emulator.
- The ratio is dimensionless: it only changes when the libc wrapper
  itself changes relative to the raw syscall — which is precisely what a
  hook causes.

Implementation details that matter:

- 200-iteration warmup before the timed runs to prime caches and branch
  predictors.
- 3 trials, taking the **minimum** of each measurement — scheduler
  preemption only inflates measurements, never deflates them, so the min
  is the cleanest signal.
- `volatile` sinks on the return values so the optimizer can't hoist the
  calls out of the loop.

Bypassed by: a hook that *adds no measurable latency*. This is very hard
in practice for Frida — even a no-op `Interceptor.attach(..., {})` callback
crosses the JNI bridge into V8 and back.

## Build / packaging notes

- The native lib is built with `-fvisibility=hidden`, `-ffunction-sections`,
  `-fdata-sections`, and linked with `-Wl,--gc-sections
  -Wl,--exclude-libs,ALL` to minimize symbol exposure for static analysis
  of the stripped `.so`.
- `ndk.abiFilters` is pinned to the four production ABIs to avoid pulling
  in `riscv64` (not supported by Android 9 / `minSdk 28`).
- The JNI entry point uses a clean (non-mangled) Kotlin signature —
  `Java_org_witness_proofmode_c2pa_DeviceIntegritySupport_nativeIsEnvironmentCompromised`
  — so it does **not** live on the `Companion` object. R8 must not rename
  it; the `proguard-rules.pro` rule enforces that.

## What still depends on the JVM

The other signals in `detectThreats()` — `isUsbConnected()` and
`isDeveloperAttackSurfaceOpen()` — remain in Kotlin. They check
`ConnectivityManager`, `BatteryManager`, and `Settings.Global` and are not
the kind of things Frida specifically targets, so the cost / benefit of
moving them to native is low.

`isFridaPresent()` calls the native checks first and then falls back to its
original JVM-side `Socket("127.0.0.1", 27042)` and `File("/proc/self/maps")`
scan. The fallback is trivially defeated by Frida (just hook
`java.net.Socket` and `java.io.File`), but it costs almost nothing and
provides a result when the native lib is absent or blocked.

## Future hardening to consider

- Hash the in-memory `.text` segments of critical libs (`libdintegrity.so`,
  `libc.so`) on startup and compare against a baseline, to detect inline
  patching.
- Check parent process (`/proc/self/status` → `PPid` → `/proc/<ppid>/comm`)
  for known launcher anomalies (`zygote` vs. something else).
- Detect ptrace attach via `/proc/self/status` `TracerPid != 0`.
- Verify that this lib was actually loaded via `dl_iterate_phdr` (catches
  agents that prevent `System.loadLibrary` from running but let the process
  continue).
- Repeat checks on a timer rather than only at startup, so late-attach
  attempts are caught.
