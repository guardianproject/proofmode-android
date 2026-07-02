# android-cid-lib

Contributor-facing module for **offline IPFS CID computation** in Proofmode's local-ipfs-cid stack. This is the **computation layer**: it turns raw bytes into UnixFS leaf CIDs and proof-set directory roots via a Kotlin facade over a Rust `cdylib`, with no dependency on `:android-libproofmode` or `:plugin-ipfs-cid`.

Use this module when changing the Rust crate, UniFFI bindings, or conformance vectors. Lifecycle policy, feature gating, and sidecar persistence live in [`:plugin-ipfs-cid`](../plugin-ipfs-cid/README.md) — not here.

Normative behavior (options contract, link naming, conformance vectors) is defined in the [local-ipfs-cid feature spec](../docs/spec/features/local-ipfs-cid/local-ipfs-cid_spec.md).

---

## Explanation

### Role in the stack

| Layer                  | Module                  | Responsibility                                                                                          |
| ---------------------- | ----------------------- | ------------------------------------------------------------------------------------------------------- |
| App wiring             | `:app`                  | `IpfsCidPlugin.register()`, feature flag — must not import or call `CidLib` directly                    |
| Lifecycle triggers     | `:android-libproofmode` | Generic `ProofWriteHook` / `ProofArtifactSavedHook` — no CID types on the libproofmode classpath          |
| Policy + orchestration | `:plugin-ipfs-cid`      | Gate, membership, scheduling, sidecar JSON I/O — depends on `:android-cid-lib` for CID math             |
| **Computation**        | **`:android-cid-lib`**  | **`CidLib`, UniFFI glue, native `librust_cid_lib.so`** — pure computation; no libproofmode dependency |

Call flow: `:app` → `:plugin-ipfs-cid` → `:android-cid-lib`. Callers that need lifecycle integration use `:plugin-ipfs-cid` instead of calling `CidLib` from `:app`.

### Public API

Hand-written entry point: `org.witness.proofmode.cid.CidLib` (Kotlin `object`).

| Method                                            | Purpose                                                           |
| ------------------------------------------------- | ----------------------------------------------------------------- |
| `computeFileCid(bytes, options?)`                 | Single-file UnixFS leaf CID                                       |
| `computeFileLeafCidAndTsize(bytes, options?)`     | Leaf CID plus `tsize` for directory building                      |
| `computeProofSetCid(entries, options?)`           | Directory root from `List<NamedBytes>` (hashes each entry)        |
| `computeProofSetCidFromLeaves(entries, options?)` | Directory root from precomputed `List<NamedLeafCid>` (no re-hash) |

Supporting types: `CidOptions`, `NamedBytes`, `NamedLeafCid`, `FileLeafCidResult`, `ProofSetCidResult`.

#### Default options (v1 contract)

| Field               | Default |
| ------------------- | ------- |
| `chunkSize`         | 262_144 |
| `cidVersion`        | 1       |
| `rawLeaves`         | true    |
| `wrapWithDirectory` | false   |
| `shardThreshold`    | 262_144 |
| `blockSizeLimit`    | null    |

Entry lists may be passed in any order; `CidLib` sorts by basename (Unicode `String.compareTo`) before invoking Rust.

#### Native loading

`System.loadLibrary("rust_cid_lib")` runs on the **first** `CidLib` call (`ensureLoaded()`), not at app startup. When the developer feature flag is off and nothing calls `CidLib`, no native code loads.

### Implementation layout

```
android-cid-lib/
├── src/main/java/org/witness/proofmode/cid/
│   ├── CidLib.kt              # Hand-written facade (keep in ProGuard)
│   ├── CidOptions.kt
│   └── uniffi/                # Generated UniFFI Kotlin bindings (do not edit)
├── src/main/jniLibs/
│   └── {abi}/librust_cid_lib.so
├── rust-cid-lib/              # Rust cdylib (rust-unixfs 0.6)
│   └── src/lib.rs
├── scripts/build-rust-cid-lib.sh
└── src/test/resources/ipfs_cid_vectors.json
```

Stack: **Kotlin → UniFFI → Rust `cdylib` → `rust-unixfs`**. No hand-written JNI glue.

Gradle runs integrity checks on every build (`preBuild` depends on both verify tasks):

- `verifyRustCidLib` — JNI `.so` hashes vs `rust-cid-lib/rust-cid-lib-hashes.txt`
- `verifyUniffiBindings` — generated `.kt` hashes vs `rust-cid-lib/rust-cid-lib-uniffi-hashes.txt`

### Consumers and boundaries

- **`:plugin-ipfs-cid`** — primary consumer; schedules computation and persists sidecars.
- **`:app`** — must **not** import `CidLib` directly (policy and hooks live in the plugin).
- **`:android-libproofmode`** — stays CID-type-free; no `org.witness.proofmode.cid` imports in `src/main`.

### ProGuard / R8

`proguard-rules.pro` keeps `CidLib` public static methods and all `org.witness.proofmode.cid.uniffi.**` classes used by the generated bridge.

---

## How-to

### 1. Prerequisites

Install and verify these before rebuilding native artifacts:

| Tool               | Notes                                                                                                                                                                                                             |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Rust toolchain** | Stable; CI uses `dtolnay/rust-toolchain@stable` with Android targets (`aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`, `x86_64-linux-android`).                                          |
| **cargo-ndk**      | `cargo install cargo-ndk --locked` (CI installs this on each run).                                                                                                                                                |
| **Android NDK**    | Set `ANDROID_NDK_HOME` to your NDK root, or rely on auto-discovery: the build script checks `$ANDROID_HOME/ndk/*` and `~/Android/Sdk/ndk/*` for a directory containing `source.properties`. CI pins **NDK r26d**. |

### 2. Build native artifacts

From the repo root:

```bash
./android-cid-lib/scripts/build-rust-cid-lib.sh
```

This script:

1. Cross-compiles `librust_cid_lib.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` into `src/main/jniLibs/`.
2. Regenerates Kotlin bindings under `src/main/java/org/witness/proofmode/cid/uniffi/` via `uniffi-bindgen`.

The script prints SHA-256 sums for every `.so` and generated `.kt` file.

### 3. Update hash manifests

After a successful build, copy the script’s SHA-256 output into the committed manifests. Paths must use the `android-cid-lib/` prefix (matching existing lines):

| Manifest                                      | Tracks                                                                                             |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `rust-cid-lib/rust-cid-lib-hashes.txt`        | One line per ABI: `{hash}  android-cid-lib/src/main/jniLibs/{abi}/librust_cid_lib.so`              |
| `rust-cid-lib/rust-cid-lib-uniffi-hashes.txt` | Generated glue: `{hash}  android-cid-lib/src/main/java/org/witness/proofmode/cid/uniffi/{file}.kt` |

Quick regeneration (paths must match manifest format):

```bash
find android-cid-lib/src/main/jniLibs -name 'librust_cid_lib.so' -exec sha256sum {} \; \
  | awk '{print $1 "  android-cid-lib/" substr($2, index($2,"src/"))}' | sort -k2

find android-cid-lib/src/main/java/org/witness/proofmode/cid/uniffi -name '*.kt' -exec sha256sum {} \; \
  | awk '{print $1 "  android-cid-lib/" substr($2, index($2,"src/"))}' | sort -k2
```

### 4. What to commit

**Commit** after any native or binding change:

- `src/main/jniLibs/**/librust_cid_lib.so` (all four ABIs)
- `src/main/java/org/witness/proofmode/cid/uniffi/*.kt` (generated glue)
- `rust-cid-lib/rust-cid-lib-hashes.txt` and `rust-cid-lib/rust-cid-lib-uniffi-hashes.txt`

**Do not commit** Rust `target/` build output or other ephemeral artifacts outside the paths above.

Kotlin-only changes to the hand-written facade (`CidLib.kt`, `CidOptions.kt`, tests) do not require a native rebuild unless the Rust/UniFFI contract changes.

### 5. CI

[`.github/workflows/rust-cid-lib.yml`](../.github/workflows/rust-cid-lib.yml) runs on pull requests and `main` pushes that touch native paths (`rust-cid-lib/**`, jniLibs, UniFFI glue, hash manifests, build script, or this module’s `build.gradle.kts`).

The workflow rebuilds from source, diffs SHA-256 output against the committed manifests, asserts no uncommitted UniFFI glue, runs both Gradle verify tasks, and runs `cargo test` in `rust-cid-lib/`.

**Local vs CI:** Most contributors rely on committed prebuilts and pass verify tasks without rebuilding. If you change Rust or bindings, you must rebuild locally, update manifests, and commit the artifacts — otherwise `preBuild` verify tasks and CI will fail on hash mismatch.

### 6. Verify and test

```bash
# Integrity checks (also run automatically on preBuild)
./gradlew :android-cid-lib:verifyRustCidLib :android-cid-lib:verifyUniffiBindings

# JVM unit tests (conformance vectors; skips native cases when .so unavailable)
./gradlew :android-cid-lib:testDebugUnitTest

# Conformance suite only
./gradlew :android-cid-lib:testDebugUnitTest --tests org.witness.proofmode.cid.IpfsCidConformanceTest

# Instrumented (device/emulator; exercises native load on device)
./gradlew :android-cid-lib:connectedDebugAndroidTest
```

Shared fixture: `src/test/resources/ipfs_cid_vectors.json` — pins exact CIDs for leaf, proof-set (F2), and precomputed-leaf (F2a) paths.

### When do I need to rebuild?

| Change                                            | Rebuild native?                                                     |
| ------------------------------------------------- | ------------------------------------------------------------------- |
| `rust-cid-lib/**`, build script, UniFFI interface | **Yes** — run build script, update manifests, commit `.so` + `.kt`  |
| Hand-written Kotlin facade or tests only          | **No** (unless Rust contract changed)                               |
| `:plugin-ipfs-cid` Kotlin only                    | **No** — see [plugin-ipfs-cid README](../plugin-ipfs-cid/README.md) |

### Fork integrators

Include modules in `settings.gradle` and depend from your app or plugin:

```kotlin
// settings.gradle — include ':android-cid-lib' (and ':plugin-ipfs-cid' if needed)

dependencies {
    implementation(project(":android-cid-lib"))
    // implementation(project(":plugin-ipfs-cid"))  // policy layer; depends on android-cid-lib
}
```

There is no Maven publish for v1; use `project()` wiring in a fork.

---

## Further reading

- [local-ipfs-cid feature spec](../docs/spec/features/local-ipfs-cid/local-ipfs-cid_spec.md) — normative product behavior
- [rust-unixfs](https://crates.io/crates/rust-unixfs) — UnixFS CID computation crate
- [rust-cid](https://github.com/multiformats/rust-cid) — CID primitives
- [UniFFI user guide](https://mozilla.github.io/uniffi-rs/latest/)
