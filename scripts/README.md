# Scripts

## `register_lp_eas_schema.dart`

Idempotent registration of the Location Protocol EAS schema on every chain in `SUPPORTED_CHAINS` (Ethereum Mainnet, Arbitrum One, Base, Sepolia, Arbitrum Sepolia).

```bash
cd scripts
dart pub get
dart run register_lp_eas_schema.dart --help
dart run register_lp_eas_schema.dart --dry-run
dart run register_lp_eas_schema.dart
```

Copy `.env.example` to `.env` and set `DEPLOYER_PRIVATE_KEY` before registering. The deployer wallet must hold native gas on each target chain.

See the script header and `--help` for RPC overrides and schema UID derivation details.
