// Native-gas balance of the deployer wallet on every chain the schema
// registration script targets. Read-only: never sends a transaction.
//
// Usage:
//   cd scripts && dart pub get
//   dart run check_deployer_balances.dart
//   dart run check_deployer_balances.dart --chains 534352,57073
//
// Environment (scripts/.env or process env):
//   DEPLOYER_PRIVATE_KEY  — only used to derive the deployer address
//   <CHAIN>_RPC_URL       — optional per-chain override, same keys as
//                           register_lp_eas_schema.dart
//
// Exit code is 1 when any listed chain has a zero balance, so the funding
// wizard can loop until every chain is funded.

import 'dart:convert';
import 'dart:io';

import 'package:location_protocol/src/rpc/default_rpc_provider.dart';

/// Below this many wei the chain is reported as needing funds.
/// 0.0005 ETH comfortably covers one SchemaRegistry.register on an L2.
const int _defaultMinimumWei = 500000000000000;

/// Celo pays gas in CELO at ~200 gwei (mainnet) and ~50 gwei (Sepolia), so
/// one register costs ~0.05 CELO and the ETH default would pass on dust.
const int _celoMinimumWei = 100000000000000000; // 0.1 CELO

class _Chain {
  const _Chain(this.chainId, this.displayName, this.rpcEnvKey, this.rpcUrls,
      {this.minimumWei = _defaultMinimumWei});
  final int chainId;
  final String displayName;
  final String rpcEnvKey;
  final List<String> rpcUrls;
  final int minimumWei;
}

/// Keep in sync with _chainTargets in register_lp_eas_schema.dart.
const _chains = [
  _Chain(1, 'Ethereum Mainnet', 'ETHEREUM_RPC_URL', [
    'https://eth.api.pocket.network',
    'https://ethereum-rpc.publicnode.com',
  ]),
  _Chain(42161, 'Arbitrum One', 'ARBITRUM_RPC_URL', [
    'https://arb-one.api.pocket.network',
  ]),
  _Chain(8453, 'Base', 'BASE_RPC_URL', ['https://base.api.pocket.network']),
  _Chain(10, 'Optimism', 'OPTIMISM_RPC_URL', [
    'https://op.api.pocket.network',
    'https://optimism-rpc.publicnode.com',
  ]),
  _Chain(534352, 'Scroll', 'SCROLL_RPC_URL', [
    'https://scroll.api.pocket.network',
    'https://scroll-rpc.publicnode.com',
  ]),
  _Chain(57073, 'Ink', 'INK_RPC_URL', [
    'https://ink.api.pocket.network',
    'https://rpc-gel.inkonchain.com',
  ]),
  _Chain(11155111, 'Sepolia Testnet', 'SEPOLIA_RPC_URL', [
    'https://eth-sepolia-testnet.api.pocket.network',
    'https://ethereum-sepolia-rpc.publicnode.com',
  ]),
  _Chain(421614, 'Arbitrum Sepolia', 'ARBITRUM_SEPOLIA_RPC_URL', [
    'https://arb-sepolia-testnet.api.pocket.network',
    'https://arbitrum-sepolia-rpc.publicnode.com',
  ]),
  _Chain(11155420, 'Optimism Sepolia', 'OPTIMISM_SEPOLIA_RPC_URL', [
    'https://op-sepolia-testnet.api.pocket.network',
    'https://optimism-sepolia-rpc.publicnode.com',
  ]),
  _Chain(84532, 'Base Sepolia', 'BASE_SEPOLIA_RPC_URL', [
    'https://base-sepolia-testnet.api.pocket.network',
    'https://base-sepolia-rpc.publicnode.com',
  ]),
  _Chain(763373, 'Ink Sepolia', 'INK_SEPOLIA_RPC_URL', [
    'https://rpc-gel-sepolia.inkonchain.com',
    'https://rpc-qnd-sepolia.inkonchain.com',
  ]),
  _Chain(11142220, 'Celo Sepolia', 'Celo_SEPOLIA_RPC_URL', [
    'https://rpc.ankr.com/celo_sepolia',
    'https://celo-sepolia.drpc.org',
  ], minimumWei: _celoMinimumWei),
  _Chain(42220, 'Celo', 'Celo_RPC_URL', [
    'https://rpc.ankr.com/celo',
    'https://celo-json-rpc.stakely.io',
  ], minimumWei: _celoMinimumWei),
];

Future<void> main(List<String> args) async {
  if (args.contains('--help') || args.contains('-h')) {
    stdout.writeln('Usage: dart run check_deployer_balances.dart [--chains id,id,...]');
    return;
  }

  final env = _loadDotEnv();
  final privateKey = env['DEPLOYER_PRIVATE_KEY'] ??
      Platform.environment['DEPLOYER_PRIVATE_KEY'] ??
      env['PRIVATE_KEY'] ??
      Platform.environment['PRIVATE_KEY'];
  if (privateKey == null || !privateKey.startsWith('0x') || privateKey.length != 66) {
    stderr.writeln('❌ DEPLOYER_PRIVATE_KEY must be set (0x-prefixed, 66 chars) in scripts/.env');
    exitCode = 64;
    return;
  }

  // Only used to derive the address; chainId and rpcUrl are irrelevant here.
  final address = DefaultRpcProvider(
    rpcUrl: _chains.first.rpcUrls.first,
    privateKeyHex: privateKey,
    chainId: 1,
  ).signerAddress;

  final wanted = _parseChainsArg(args);
  final targets = wanted == null
      ? _chains
      : _chains.where((c) => wanted.contains(c.chainId)).toList();

  stdout.writeln('Deployer address: $address');
  stdout.writeln('');
  stdout.writeln('${'Chain'.padRight(18)} ${'Chain ID'.padRight(10)} ${'Balance'.padRight(16)} Status');

  var unfunded = 0;
  for (final chain in targets) {
    final rpcUrls = _resolveRpcUrls(env, chain);
    final wei = await _balance(address, rpcUrls);
    final String balanceText;
    final String status;
    if (wei == null) {
      balanceText = '?';
      status = 'RPC unreachable';
      unfunded++;
    } else {
      balanceText = _formatEth(wei);
      if (wei >= BigInt.from(chain.minimumWei)) {
        status = 'funded';
      } else {
        status = 'NEEDS FUNDS';
        unfunded++;
      }
    }
    stdout.writeln(
      '${chain.displayName.padRight(18)} ${chain.chainId.toString().padRight(10)} ${balanceText.padRight(16)} $status',
    );
  }

  stdout.writeln('');
  if (unfunded == 0) {
    stdout.writeln('✅ Every listed chain is funded.');
  } else {
    stdout.writeln('⚠ $unfunded chain(s) still need gas.');
    exitCode = 1;
  }
}

List<int>? _parseChainsArg(List<String> args) {
  final index = args.indexOf('--chains');
  if (index < 0 || index + 1 >= args.length) return null;
  return args[index + 1]
      .split(',')
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty)
      .map(int.parse)
      .toList();
}

List<String> _resolveRpcUrls(Map<String, String> env, _Chain chain) {
  final override = env[chain.rpcEnvKey] ?? Platform.environment[chain.rpcEnvKey];
  if (override != null && override.isNotEmpty) return [override];
  return chain.rpcUrls;
}

Future<BigInt?> _balance(String address, List<String> rpcUrls) async {
  for (final rpcUrl in rpcUrls) {
    final client = HttpClient();
    try {
      final request = await client.postUrl(Uri.parse(rpcUrl));
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode({
        'jsonrpc': '2.0',
        'method': 'eth_getBalance',
        'params': [address, 'latest'],
        'id': 1,
      }));
      final response = await request.close();
      if (response.statusCode != 200) continue;
      final body = await response.transform(utf8.decoder).join();
      final decoded = jsonDecode(body) as Map<String, dynamic>;
      final result = decoded['result'];
      if (result is! String || !result.startsWith('0x')) continue;
      return BigInt.parse(result.substring(2), radix: 16);
    } catch (_) {
      continue;
    } finally {
      client.close(force: true);
    }
  }
  return null;
}

String _formatEth(BigInt wei) {
  final whole = wei ~/ BigInt.from(10).pow(18);
  final frac = (wei % BigInt.from(10).pow(18)).toString().padLeft(18, '0');
  return '$whole.${frac.substring(0, 6)}';
}

Map<String, String> _loadDotEnv({String path = '.env'}) {
  final file = File(path);
  if (!file.existsSync()) return <String, String>{};
  final env = <String, String>{};
  for (final line in file.readAsLinesSync()) {
    final trimmed = line.trim();
    if (trimmed.isEmpty || trimmed.startsWith('#')) continue;
    final eq = trimmed.indexOf('=');
    if (eq < 0) continue;
    final key = trimmed.substring(0, eq).trim();
    var value = trimmed.substring(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.substring(1, value.length - 1);
    }
    if (key.isNotEmpty && value.isNotEmpty) env[key] = value;
  }
  return env;
}
