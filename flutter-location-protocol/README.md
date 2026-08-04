# flutter-location-protocol

A headless Flutter module used by ProofMode Android as a passive Dart runtime
for building cryptographically verifiable LP attestation data.

Kotlin drives all method calls. Dart uses the `location_protocol` package to
construct EAS EIP-712 typed-data and assemble signed attestation results from
Privy-provided signatures. No key material, wallet state, or transaction logic
exists in this package.

## Architecture

```
Kotlin (host app)  ──── MethodChannel ────▶  Dart isolate
                   ◀────────────────────────  (passive responder)
```

- Kotlin is the **sole caller**. Dart never initiates method calls to Kotlin
  except for the startup `bridge/ready` liveness signal.
- The isolate boots headlessly (no Flutter UI). Entry point: `backgroundMain()`.
- The keepalive future blocks until Kotlin sends `bridge/shutdown`.

## Bridge Method Contracts

### Methods Kotlin → Dart

| Method                        | Request keys                                | Response                       |
| ----------------------------- | ------------------------------------------- | ------------------------------ |
| `ping`                        | —                                           | `"pong"`                       |
| `build-eas-typed-data`        | `payload` (map)                             | EIP-712 typed-data JSON string |
| `create-offchain-attestation` | `typedData`, `signature`, `attesterAddress` | attestation result map         |
| `bridge/shutdown`             | —                                           | `null` (completes keepalive)   |

### Methods Dart → Kotlin

| Method         | Payload | Purpose                 |
| -------------- | ------- | ----------------------- |
| `bridge/ready` | none    | Startup liveness signal |

### `build-eas-typed-data` — request

`payload` map (or JSON string) with required fields:

```
eventTimestamp   int       Unix ms
srs              string    e.g. "wgs84"
locationType     string    e.g. "geojson-point"
location         string    GeoJSON string
recipeType       string[]
recipePayload    string[]
mediaType        string[]
mediaData        string[]
memo             string    (optional, default "")
```

Response is a JSON string containing:

- Standard EIP-712 keys: `types`, `primaryType`, `domain`, `message`
- Passthrough keys (`_schemaUID`, `_saltHex`, `_encodedData`, `_recipient`,
  `_time`, `_expirationTime`, `_revocable`, `_refUID`) used by
  `create-offchain-attestation` for UID computation without re-deriving

Numeric fields (`time`, `expirationTime`, `version`, `chainId`) are decimal
strings as required by the `on_chain` package.

### `create-offchain-attestation` — request

```
typedData       string   JSON string from build-eas-typed-data
signature       string   65-byte hex from Privy eth_signTypedData_v4
attesterAddress string   Ethereum address of the Privy signer
```

Response map:

```
uid                 string   0x-prefixed keccak256 UID
schemaId            string   0x-prefixed schema UID
attesterAddress     string   echo of input
timestamp           int      Unix ms
offchainPayloadJson string   EAS-compatible JSON
artifactPath        string   "" (storage handled by Kotlin)
```

### Error codes

All errors are thrown as `PlatformException` with an `LP_`-prefixed code:

| Code                   | Meaning                         |
| ---------------------- | ------------------------------- |
| `LP_MISSING_FIELD`     | Required field absent           |
| `LP_INVALID_PAYLOAD`   | Malformed or unparseable input  |
| `LP_INVALID_SIGNATURE` | Signature hex is not 65 bytes   |
| `LP_UNKNOWN_METHOD`    | Method name not recognized      |
| `LP_ERROR`             | Catch-all for unexpected errors |

## Source Structure

```
lib/
  main.dart                        Entry point — delegates to handler
  src/
    bridge_channels.dart           MethodChannel name constant
    attestation_handler.dart       Full bridge runtime
test/
  attestation_handler_test.dart    Contract tests (ping, typed-data, attestation, errors)
```

## Running Tests

```sh
flutter test
```

## Static Analysis

```sh
flutter analyze
```

## Integration Notes

- Channel name: `org.witness.proofmode/location_protocol`
- Kotlin coordinator: `LPSigningCoordinator` in `plugin-wallet-signing`
- `attesterAddress` is resolved from `WalletSigner.address` on the Kotlin side
  before the bridge call — Dart has no wallet state access by design
- Review handoff: [`docs/spec/reviews/phase-3-privy-attestation-signer-handoff-2026-05-30.md`](../docs/spec/reviews/phase-3-privy-attestation-signer-handoff-2026-05-30.md)

## Prepare Module for Host App Compilation

Use this order of operations whenever you regenerate Flutter Android artifacts.

> Note: these steps are only necessary if you are modifying the Flutter module (e.g. updating pubspec dependencies, Flutter plugin wiring, modification of any files under `.android`) and need to re-compile the host app. If you are only modifying host app code (i.e. the dart code), you can skip these steps. Pure Dart changes do not require these steps.

**Linux / macOS:**

```sh
flutter pub get
cp flutter-android-build-shim.gradle .android/Flutter/build.gradle
./gradlew :plugin-location-protocol:compileDebugKotlin
./gradlew :app:compileDefaultDebugKotlin
```

**Windows PowerShell:**

```powershell
flutter pub get
Copy-Item "flutter-android-build-shim.gradle" ".android/Flutter/build.gradle" -Force
./gradlew.bat :plugin-location-protocol:compileDebugKotlin
./gradlew.bat :app:compileDefaultDebugKotlin
```

For AGP 9 compatibility details and shim rationale, see:
[`docs/flutter-module-compat-shim.md`](../docs/flutter-module-compat-shim.md)
