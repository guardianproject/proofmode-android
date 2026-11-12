# Scripts

## `register_lp_eas_schema.dart`

Idempotent registration of the Location Protocol EAS schema on every chain in `SUPPORTED_CHAINS` (mainnets: Ethereum, Arbitrum One, Base, Optimism, Scroll, Ink; testnets: Sepolia, Arbitrum Sepolia, Optimism Sepolia, Base Sepolia, Ink Sepolia).

```bash
cd scripts
dart pub get
dart run register_lp_eas_schema.dart --help
dart run register_lp_eas_schema.dart --dry-run
dart run register_lp_eas_schema.dart
```

Copy `.env.example` to `.env` and set `DEPLOYER_PRIVATE_KEY` before registering. The deployer wallet must hold native gas on each target chain.

See the script header and `--help` for RPC overrides and schema UID derivation details.

## `check_deployer_balances.dart`

Read-only native-gas balance of the deployer wallet on every chain above. Derives the address from `DEPLOYER_PRIVATE_KEY`, never sends anything. Exits 1 when any chain is below 0.0005 ETH.

```bash
cd scripts
dart run check_deployer_balances.dart
dart run check_deployer_balances.dart --chains 534352,57073
```

## `fund_deployer.sh`

Interactive walkthrough for funding the deployer wherever `check_deployer_balances.dart` reports it empty. Opens each bridge or faucet, tells you what to do there, and re-checks the balance before moving on. Chains that already read as funded are skipped, so it is safe to re-run.

```bash
scripts/fund_deployer.sh
```
