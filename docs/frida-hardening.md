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

## Capture-authorization gate (signing-oracle defense)

Detection alone does not stop the published Frida oracle attack
(`sign_proofmode.py` + `frida_proofsign.js`): an attacker who reflectively
instantiates `ProofSignClient` and calls `signC2PAClaimWithDeviceAuth()`
can produce valid C2PA signatures for arbitrary media that ProofMode never
captured. Play Integrity and Key Attestation only prove the device is
genuine and the app is installed; they do not prove that the bytes being
signed correspond to a real capture event in this app.

The defense is a per-capture authorization nonce, threaded through the
signing path as three concentric gates.

### CaptureAuthority

`android-libproofmode/.../c2pa/proofsign/CaptureAuthority.kt` issues
single-use 32-byte nonces, each bound at issue time to the SHA-256 digest
of the captured file. Nonces expire after 60 seconds and the outstanding
set is capped at 16 (oldest evicted). Binding to the file digest (not raw
bytes) means the same guarantee applies to multi-hundred-MB video files
without holding them in memory; binding to digest rather than URI/path
means an attacker who swaps file contents between issue and consume sees
the digests diverge and the gate close.

### Three concentric gates

| Gate | File | What it catches |
| ---- | ---- | --------------- |
| Outer | `CameraViewModel.sendLocalCameraEvent` issues the nonce; `MediaWatcher.ingestMedia` refuses to attempt C2PA at all when nonce is null. | Non-camera callers (MediaStore observers, public `ProofMode.java` API, gallery imports) — they get the PGP/hash sidecar only, no C2PA claim. |
| Middle | `C2PAManager.signMediaFile` is the *only* place that calls `CaptureAuthority.consumeNonce(...)`. It throws `UnauthorizedCaptureException` synchronously if the nonce is missing, expired, used, or bound to a different digest. On success it wraps `signStream(...)` in `CaptureAuthority.enterSigningScope(digest, ...)`. | Frida calls that skip `MediaWatcher` and invoke `signMediaFile` directly. |
| Inner | `ProofSignC2PASigner.signData` and `ProofSignClient.signC2PAClaimWithDeviceAuth` both check `CaptureAuthority.currentAuthorizedDigest()` and throw if it is null. The `ThreadLocal` is only populated inside `enterSigningScope`. | The *published* attack: `Java.use('org.witness.proofmode.c2pa.proofsign.ProofSignClient').signC2PAClaimWithDeviceAuth(...)` invoked directly from Frida. The attacker's thread has no signing scope; the synchronous throw lands in the Frida agent rather than reaching the server. |

### Behaviour change worth knowing

Under this gate **only** files coming through `CameraViewModel.sendLocalCameraEvent`
produce C2PA claims. Four other in-tree callers of `ingestMedia` now
produce PGP/hash sidecar only and no C2PA signature:

- `PhotosContentWorker.kt` / `PhotosContentJob.java` (system-camera auto-detect)
- `VideosContentWorker.kt` / `VideosContentJob.java` (same for video)
- `ProofMode.java` public library API

This is intentional — those paths never observed the capture event and
cannot honestly claim camera provenance. If any of them needs C2PA, it
must grow its own legitimate-capture story that issues a nonce.

### What still gets through

A sufficiently determined attacker who *also* hooks the camera capture
path (intercepts `sendLocalCameraEvent`, supplies attacker-controlled
image bytes, lets the legitimate `issueNonce` fire) can still mint a
valid signature for their own content. That bypass is several orders of
magnitude harder than the documented attack, requires precise timing
against a real capture event, and leaves much more forensic signal. The
complementary defense is the server-side claim-binding contract
([sketch in proofmode-hardening/patches/server-contract/SERVER_CONTRACT.md](../../Downloads/ph/proofmode-hardening/patches/server-contract/SERVER_CONTRACT.md))
that requires the server's Play-Integrity-bound nonce to be bound to the
claim content — making content-swap detectable server-side as well.

## Runtime integrity recheck at signing time

The native checks in `DeviceIntegritySupport.isEnvironmentCompromised()`
historically ran only at `ProofModeApp.onCreate` and
`CameraActivity.onCreate`, with `System.exit(0)` on a hit. The published
attack flow specifically defeats that: launch ProofMode first (so
`onCreate` runs clean), *then* start `frida-server`, *then* attach. After
startup, nothing rechecks.

The runtime recheck closes this gap. Two entry points now call
`isEnvironmentCompromised()` synchronously at the top, throwing
`CompromisedEnvironmentException` on a hit:

- `C2PAManager.signMediaFile` — covers all signing modes (KEYSTORE,
  HARDWARE, REMOTE), so it gates local signers too.
- `ProofSignClient.signC2PAClaimWithDeviceAuth` — fires *before*
  `scope.launch`, so the throw is delivered synchronously to a direct
  Frida invocation rather than being swallowed by coroutine machinery.

`MediaWatcher.ingestMedia` catches `CompromisedEnvironmentException` the
same way it catches `UnauthorizedCaptureException` — the compromised
device still gets a PGP/hash sidecar, just not a C2PA claim.

Cost: roughly 10–25 ms per sign (mostly the timing probe at 30k
syscalls), against a multi-hundred-ms server round trip. Negligible.

## R8 obfuscation of the proofsign package

The published attack agent hardcodes class and method names that R8 was
*intended* to obfuscate. A previous proguard rule `-keep class org.**`
was over-broad and overrode the more specific
`-keep,allowobfuscation class org.witness.proofmode.c2pa.proofsign.**`
(R8 applies the strictest constraint when multiple rules match). The
proofsign package therefore kept its original names in release builds.

The proguard rules were reworked in `app/proguard-rules.pro` to:

- Replace the over-broad keep with subpackage-specific keeps for
  everything in `org.witness.proofmode.*` *except* `proofsign`.
- Add kotlinx.serialization rules for the `@Serializable`
  `SignerConfiguration` so JSON deserialization keeps working with
  obfuscated names.
- Leave the existing `-keep,allowobfuscation` rule in place so the
  package's classes are kept (no shrinking) but renamed.

After the change, the R8 mapping shows the attack-targeted symbols are
fully obfuscated:

| Original | Obfuscated |
| -------- | ---------- |
| `ProofSignClient` | `org.witness.proofmode.c2pa.proofsign.e` |
| `ProofSignClient.signC2PAClaimWithDeviceAuth` | `e.y(String, Function1)` |
| `ProofSignClient.verifyDevice` | `e.A(Function1)` |
| `Result` / `Result$Success` / `Result$Failure` | `f` / `f$b` / `f$a` |
| `C2PABearerSignature` | `a` |
| `CaptureAuthority` | `b` |
| `ClaimBinding` | `c` |
| `CompromisedEnvironmentException` | `d` |

The Frida agent's hardcoded `Java.use('...ProofSignClient')` now throws
`ClassNotFoundException`, and even after recovering the renamed class,
`.signC2PAClaimWithDeviceAuth(...)` is `NoSuchMethodError` (the method
is now `.y(...)`).

## Defense stack against the published attack

In execution order, the gates the published `sign_proofmode.py` +
`frida_proofsign.js` hits against a hardened release build:

1. `Java.use('org.witness.proofmode.c2pa.proofsign.ProofSignClient')` →
   **`ClassNotFoundException`** (class renamed by R8 to `…proofsign.e`).
2. Even after recovering the name, the agent's 4-arg
   `.$new(ctx, url, project, mode)` →
   **`NoSuchMethodError`** (the current constructor is 3-arg;
   `AttestationMode` is dead-code-eliminated and absent from the build).
3. Even after fixing the constructor, `signC2PAClaimWithDeviceAuth` →
   renamed to `y`, **`NoSuchMethodError`**.
4. Even after finding `y`, the first line throws
   **`CompromisedEnvironmentException`** because the runtime integrity
   recheck sees the Frida agent in `/proc/self/maps`, a `gum-js` thread,
   a hooked libc, or a timing anomaly.
5. Even after hooking `isEnvironmentCompromised` to return false, the
   next line throws **`UnauthorizedCaptureException`** because the
   `CaptureAuthority` `ThreadLocal` is empty on the Frida thread.
6. Even after hooking `currentAuthorizedDigest` to return non-null,
   each new hook is itself a Frida fingerprint that future timing /
   maps / syscall-integrity checks (run on each sign) can pick up — and
   the attacker now needs to maintain multiple hooks in sync.

## Known leaks and future work

These are the residual weaknesses in the current design, in roughly
descending order of how much they matter.

### Kotlin internal getter exposes the ThreadLocal directly

`CaptureAuthority.enterSigningScope` is `inline`, which prevents Frida
from calling it directly at runtime (inline functions have no JVM method
to hook). But the underlying `ThreadLocal` is reachable through the
`@PublishedApi internal val activeDigestThreadLocal` getter, which
Kotlin compiles to a public JVM method with a mangled name like
`getActiveDigestThreadLocal$android_libproofmode_release`. A
sophisticated attacker can call that getter, `.set(fakeDigest)`, and
bypass the inner gate without hooking anything.

Possible fixes:
- Move `activeDigest` storage into the native lib so there is no JVM
  ThreadLocal to manipulate. The Kotlin side calls a JNI method to
  read/write it; the native side stores it in TLS keyed by `gettid()`.
- Or wrap the ThreadLocal value in a small opaque token that includes
  an HMAC over `(threadId, digest, salt)` where the salt lives only in
  native memory — `.set()` from Frida can't produce a valid token
  because it doesn't know the salt.

### Native checks are callable through JVM wrappers

`isEnvironmentCompromised()` is a Kotlin method that calls the JNI
`nativeIsEnvironmentCompromised()` underneath. Frida hooks JVM methods,
not native functions, so `.implementation = function() { return false; }`
on the Kotlin wrapper bypasses every native check at once.

Fix: invoke the native function from a *native* caller in the signing
path rather than from Kotlin. Concretely, the signing gates would call
into a small native function that runs `nativeIsEnvironmentCompromised()`
internally and then makes a follow-on JNI call to set the
`CaptureAuthority` scope. Frida can still hook the outer signing method,
but the native-to-native call is not interceptable through
`Java.use(...).implementation`.

### Server has no claim-binding contract

Every defense above is in-process and falls if a determined attacker
patches enough of it (kernel-level hook, custom Frida build, repacked
APK with our code stripped out). The only defense that doesn't depend
on the client being uncompromised is server-side claim binding: the
ProofSign server's Play-Integrity-nonce becomes bound to a hash of the
actual claim content, and the server refuses to sign content that
wasn't pre-committed.

The sketch is at
[proofmode-hardening/patches/server-contract/SERVER_CONTRACT.md](../../Downloads/ph/proofmode-hardening/patches/server-contract/SERVER_CONTRACT.md).
This is a server-team task, not a client one, and is the single
highest-leverage outstanding hardening.

### Obfuscation leak: `ProofSignC2PASigner` + `SignerConfiguration`

The kotlinx.serialization keep rule
`-keep,includedescriptorclasses class **$$serializer { *; }`
transitively pins `ProofSignC2PASigner$SignerConfiguration` (the
`@Serializable` outer holder) and its enclosing
`ProofSignC2PASigner` class. Their names survive R8 unchanged:

```
ProofSignC2PASigner                          -> ProofSignC2PASigner
ProofSignC2PASigner$SignerConfiguration      -> …$SignerConfiguration
```

The attack agent doesn't reference these classes, so the leak is not
*functionally* exploited. But a fingerprint-scanning attacker can use
the surviving names as a foothold to locate the renamed neighbours
(`ProofSignClient`, `CaptureAuthority`) in the .dex.

Fix: move `SignerConfiguration` to a top-level file in a different
package (e.g. `org.witness.proofmode.c2pa.config`), so the
`**$$serializer` rule no longer transitively pins anything adjacent to
`ProofSignClient`.

### `verifyDevice` is intentionally ungated

The capture-authorization gate covers signing; it deliberately does not
cover `verifyDevice`, which is the device-registration / re-attestation
flow. A Frida-driven `verifyDevice` call costs the attacker nothing
useful by itself — it does not produce a signature — but it does let
them observe the Play Integrity / Key Attestation flow and exercise the
device-key. If we ever change the registration flow such that
`verifyDevice` returns or persists sensitive state, this gate must be
added.

### Camera-pipeline hook bypass

Documented above under "What still gets through": an attacker who
hooks `CameraViewModel.sendLocalCameraEvent` to inject attacker-chosen
image bytes will receive a legitimately-issued nonce and produce a
valid signature. The fix is the server-side claim binding listed above;
no client-side fix can fully close this, because the attacker is by
construction inside the trust boundary at that point.

### Mapping file is a forensic asset

R8 produces `app/build/outputs/mapping/release/mapping.txt`. If this
file leaks to an attacker, every R8-renamed symbol becomes a one-shot
lookup. The mapping file is a build artefact, not a runtime asset, so
the fix is operational: gate it in CI (do not publish it alongside the
APK), and rotate the mapping each release by enabling
`-classobfuscationdictionary` / `-packageobfuscationdictionary` so old
mappings stop being useful.

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
