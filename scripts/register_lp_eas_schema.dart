// Idempotent EAS schema registration for Proofmode Location Protocol attestations.
//
// Registers the canonical LP schema (matching attestation_handler.dart) on each
// chain in SUPPORTED_CHAINS. Checks SchemaRegistry.getSchema before sending a
// register transaction — safe to re-run.
//
// Usage:
//   cd scripts && dart pub get
//   dart run register_lp_eas_schema.dart --help
//   dart run register_lp_eas_schema.dart --dry-run
//   dart run register_lp_eas_schema.dart
//   dart run register_lp_eas_schema.dart --chains 11155111,8453
//
// Environment (scripts/.env or process env):
//   DEPLOYER_PRIVATE_KEY  — 0x-prefixed 32-byte hex (required unless --dry-run)
//   <CHAIN>_RPC_URL       — optional override per chain (see _chainTargets below)
//
// Schema UID is computed locally:
//   keccak256(abi.encodePacked(schemaString, resolverAddress, revocable))
// where schemaString includes auto-prepended LP base fields.

import 'dart:convert';
import 'dart:io';

import 'package:location_protocol/src/config/chain_config.dart';
import 'package:location_protocol/src/eas/schema_registry.dart';
import 'package:location_protocol/src/rpc/default_rpc_provider.dart';
import 'package:location_protocol/src/schema/schema_definition.dart';
import 'package:location_protocol/src/schema/schema_field.dart';
import 'package:location_protocol/src/schema/schema_uid.dart';

/// EAS SchemaRegistry.getSchema(bytes32) selector — matches EthJsonRpcClient.kt.
const _getSchemaSelector = '0xa2ea7c6e';

/// Chains aligned with plugin-location-protocol/.../config/ChainConfig.kt SUPPORTED_CHAINS.
/// Scroll Sepolia (534351) is deliberately absent: the network was shut down after 2026-07-30.
const _supportedChainIds = [
  1, 42161, 8453, 10, 534352, 57073, 42220, // mainnets
  11155111, 421614, 11155420, 84532, 763373, 11142220, // testnets
];

/// EAS addresses for chains location_protocol 0.1.0 does not list. Celo
/// Sepolia ships EAS as the OP Stack predeploys: EAS.getSchemaRegistry() on
/// 0x…21 returns 0x…20, and EAS.version() is 1.4.1-beta.3.
const _easAddressOverrides = {
  11142220: ChainAddresses(
    eas: '0x4200000000000000000000000000000000000021',
    schemaRegistry: '0x4200000000000000000000000000000000000020',
    chainName: 'Celo Sepolia',
    easVersion: '1.4.1-beta.3',
  ),
};

class _ChainTarget {
  const _ChainTarget({
    required this.chainId,
    required this.displayName,
    required this.defaultRpcUrls,
    required this.rpcEnvKey,
  });

  final int chainId;
  final String displayName;
  final List<String> defaultRpcUrls;
  final String rpcEnvKey;
}

const _chainTargets = [
  _ChainTarget(
    chainId: 1,
    displayName: 'Ethereum Mainnet',
    defaultRpcUrls: [
      'https://eth.api.pocket.network',
      'https://ethereum-rpc.publicnode.com',
    ],
    rpcEnvKey: 'ETHEREUM_RPC_URL',
  ),
  _ChainTarget(
    chainId: 42161,
    displayName: 'Arbitrum One',
    defaultRpcUrls: ['https://arb-one.api.pocket.network'],
    rpcEnvKey: 'ARBITRUM_RPC_URL',
  ),
  _ChainTarget(
    chainId: 8453,
    displayName: 'Base',
    defaultRpcUrls: ['https://base.api.pocket.network'],
    rpcEnvKey: 'BASE_RPC_URL',
  ),
  _ChainTarget(
    chainId: 10,
    displayName: 'Optimism',
    defaultRpcUrls: [
      'https://op.api.pocket.network',
      'https://optimism-rpc.publicnode.com',
    ],
    rpcEnvKey: 'OPTIMISM_RPC_URL',
  ),
  _ChainTarget(
    chainId: 534352,
    displayName: 'Scroll',
    defaultRpcUrls: [
      'https://scroll.api.pocket.network',
      'https://scroll-rpc.publicnode.com',
    ],
    rpcEnvKey: 'SCROLL_RPC_URL',
  ),
  _ChainTarget(
    chainId: 57073,
    displayName: 'Ink',
    defaultRpcUrls: [
      'https://ink.api.pocket.network',
      'https://rpc-gel.inkonchain.com',
    ],
    rpcEnvKey: 'INK_RPC_URL',
  ),
  _ChainTarget(
    chainId: 11155111,
    displayName: 'Sepolia Testnet',
    defaultRpcUrls: [
      'https://eth-sepolia-testnet.api.pocket.network',
      'https://ethereum-sepolia-rpc.publicnode.com',
    ],
    rpcEnvKey: 'SEPOLIA_RPC_URL',
  ),
  _ChainTarget(
    chainId: 421614,
    displayName: 'Arbitrum Sepolia',
    defaultRpcUrls: [
      'https://arb-sepolia-testnet.api.pocket.network',
      'https://arbitrum-sepolia-rpc.publicnode.com',
    ],
    rpcEnvKey: 'ARBITRUM_SEPOLIA_RPC_URL',
  ),
  _ChainTarget(
    chainId: 11155420,
    displayName: 'Optimism Sepolia',
    defaultRpcUrls: [
      'https://op-sepolia-testnet.api.pocket.network',
      'https://optimism-sepolia-rpc.publicnode.com',
    ],
    rpcEnvKey: 'OPTIMISM_SEPOLIA_RPC_URL',
  ),
  _ChainTarget(
    chainId: 84532,
    displayName: 'Base Sepolia',
    defaultRpcUrls: [
      'https://base-sepolia-testnet.api.pocket.network',
      'https://base-sepolia-rpc.publicnode.com',
    ],
    rpcEnvKey: 'BASE_SEPOLIA_RPC_URL',
  ),
  _ChainTarget(
    chainId: 763373,
    displayName: 'Ink Sepolia',
    defaultRpcUrls: [
      'https://rpc-gel-sepolia.inkonchain.com',
      'https://rpc-qnd-sepolia.inkonchain.com',
    ],
    rpcEnvKey: 'INK_SEPOLIA_RPC_URL',
  ),
  _ChainTarget(
    chainId: 42220,
    displayName: 'Celo',
    defaultRpcUrls: [
      'https://rpc.ankr.com/celo',
      'https://celo-json-rpc.stakely.io',
    ],
    rpcEnvKey: 'Celo_RPC_URL',
  ),
  _ChainTarget(
    chainId: 11142220,
    displayName: 'Celo Sepolia',
    defaultRpcUrls: [
      'https://rpc.ankr.com/celo_sepolia',
      'https://celo-sepolia.drpc.org',
    ],
    rpcEnvKey: 'Celo_SEPOLIA_RPC_URL',
  ),
];

/// Canonical schema used by flutter-location-protocol attestation_handler.dart.
SchemaDefinition locationProtocolSchema() => SchemaDefinition(
      fields: [
        SchemaField(type: 'uint256', name: 'event_timestamp'),
        SchemaField(type: 'string', name: 'recipe_type'),
        SchemaField(type: 'string', name: 'recipe_payload'),
        SchemaField(type: 'string', name: 'media_type'),
        SchemaField(type: 'string', name: 'media_data'),
        SchemaField(type: 'string', name: 'memo'),
      ],
    );

Future<void> main(List<String> args) async {
  if (args.contains('--help') || args.contains('-h')) {
    _printHelp();
    return;
  }

  final dryRun = args.contains('--dry-run');
  final chainsArg = _parseChainsArg(args);
  final env = _loadDotEnv();
  final privateKey = _resolvePrivateKey(env);

  if (!dryRun) {
    final keyError = _validatePrivateKey(privateKey);
    if (keyError != null) {
      stderr.writeln('❌ $keyError');
      stderr.writeln('Use --dry-run to check schema status without a key.');
      exitCode = 64;
      return;
    }
  }

  final schema = locationProtocolSchema();
  final schemaString = schema.toEASSchemaString();
  final schemaUid = SchemaUID.compute(schema);

  stdout.writeln('Location Protocol EAS schema registration');
  stdout.writeln('Schema string: $schemaString');
  stdout.writeln('Schema UID:    $schemaUid');
  stdout.writeln('Revocable:     ${schema.revocable}');
  stdout.writeln('Resolver:      ${schema.resolverAddress}');
  stdout.writeln('Mode:          ${dryRun ? 'dry-run (read-only)' : 'register'}');
  stdout.writeln('');

  final targets = _selectTargets(chainsArg);
  var hadError = false;

  for (final target in targets) {
    final chainAddresses =
        _easAddressOverrides[target.chainId] ?? ChainConfig.forChainId(target.chainId);
    if (chainAddresses == null) {
      stderr.writeln('❌ ${target.displayName}: no EAS config for chain ${target.chainId}');
      hadError = true;
      continue;
    }

    final rpcUrls = _resolveRpcUrls(env, target);
    if (rpcUrls.isEmpty) {
      stderr.writeln(
        '❌ ${target.displayName}: no RPC URL (set ${target.rpcEnvKey} or use defaults)',
      );
      hadError = true;
      continue;
    }

    stdout.writeln('── ${target.displayName} (chainId=${target.chainId}) ──');
    stdout.writeln('   RPC:              ${rpcUrls.first}');
    if (rpcUrls.length > 1) {
      stdout.writeln('   RPC fallbacks:    ${rpcUrls.length - 1}');
    }
    stdout.writeln('   SchemaRegistry:   ${chainAddresses.schemaRegistry}');
    stdout.writeln('   EAS:              ${chainAddresses.eas}');

    DefaultRpcProvider? provider;
    try {
      final existing = await _isSchemaRegistered(
        rpcUrls: rpcUrls,
        schemaRegistryAddress: chainAddresses.schemaRegistry,
        schemaUid: schemaUid,
      );

      if (existing) {
        stdout.writeln('   Status:           already registered');
        stdout.writeln('   UID:              $schemaUid');
        stdout.writeln('');
        continue;
      }

      if (dryRun) {
        stdout.writeln('   Status:           NOT registered (would register)');
        stdout.writeln('');
        continue;
      }

      final rpcUrl = await _firstReachableRpc(rpcUrls);
      provider = DefaultRpcProvider(
        rpcUrl: rpcUrl,
        privateKeyHex: _normalizePrivateKeyHex(privateKey!),
        chainId: target.chainId,
      );
      final registry = SchemaRegistryClient(
        provider: provider,
        schemaRegistryAddress: chainAddresses.schemaRegistry,
      );

      stdout.writeln('   Status:           registering...');
      final callData = SchemaRegistryClient.buildRegisterCallData(schema);
      final txHash = await provider.sendTransaction(
        to: registry.contractAddress,
        data: callData,
      );
      stdout.writeln('   Status:           newly registered');
      stdout.writeln('   TX hash:          $txHash');
      stdout.writeln('   UID:              $schemaUid');
      stdout.writeln('');
    } catch (error, stackTrace) {
      stderr.writeln('❌ ${target.displayName}: $error');
      if (Platform.environment['DEBUG'] == '1') {
        stderr.writeln(stackTrace);
      }
      hadError = true;
      stdout.writeln('');
    } finally {
      provider?.close();
    }
  }

  if (hadError) {
    exitCode = 1;
  }
}

void _printHelp() {
  stdout.writeln('''
register_lp_eas_schema.dart — idempotent LP EAS schema registration

Registers the canonical Location Protocol schema on each supported network.
Checks SchemaRegistry.getSchema before broadcasting register().

Supported chains (match SUPPORTED_CHAINS in ChainConfig.kt):
  1        Ethereum Mainnet
  42161    Arbitrum One
  8453     Base
  10       Optimism
  534352   Scroll
  57073    Ink
  42220    Celo
  11155111 Sepolia Testnet
  421614   Arbitrum Sepolia
  11155420 Optimism Sepolia
  84532    Base Sepolia
  763373   Ink Sepolia
  11142220 Celo Sepolia

Options:
  --help, -h       Show this help
  --dry-run        Check registration status only (no private key required)
  --chains <ids>   Comma-separated chain IDs (default: all supported)

Environment (scripts/.env or process env):
  DEPLOYER_PRIVATE_KEY   Required unless --dry-run (0x + 64 hex chars)
  <CHAIN>_RPC_URL        Optional RPC override per chain, e.g. BASE_RPC_URL,
                         Celo_RPC_URL (rpcEnvKey in _chainTargets)

Schema UID derivation:
  keccak256(abi.encodePacked(schemaString, resolverAddress, revocable))
  schemaString = LP base fields + user fields from attestation_handler.dart

Examples:
  cd scripts && dart pub get
  dart run register_lp_eas_schema.dart --dry-run
  dart run register_lp_eas_schema.dart --chains 11155111
  dart run register_lp_eas_schema.dart
''');
}

List<int>? _parseChainsArg(List<String> args) {
  final index = args.indexOf('--chains');
  if (index < 0 || index + 1 >= args.length) return null;
  final raw = args[index + 1];
  return raw
      .split(',')
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty)
      .map(int.parse)
      .toList();
}

List<_ChainTarget> _selectTargets(List<int>? chainIds) {
  if (chainIds == null) {
    return _chainTargets.where((t) => _supportedChainIds.contains(t.chainId)).toList();
  }
  return _chainTargets.where((t) => chainIds.contains(t.chainId)).toList();
}

String? _resolvePrivateKey(Map<String, String> env) {
  return env['DEPLOYER_PRIVATE_KEY'] ??
      Platform.environment['DEPLOYER_PRIVATE_KEY'] ??
      env['PRIVATE_KEY'] ??
      Platform.environment['PRIVATE_KEY'];
}

String? _validatePrivateKey(String? privateKey) {
  if (privateKey == null || privateKey.isEmpty) {
    return 'DEPLOYER_PRIVATE_KEY is required for registration.';
  }
  if (!privateKey.startsWith('0x') || privateKey.length != 66) {
    return 'DEPLOYER_PRIVATE_KEY must be 0x-prefixed and 66 characters.';
  }
  return null;
}

List<String> _resolveRpcUrls(Map<String, String> env, _ChainTarget target) {
  final override = env[target.rpcEnvKey] ?? Platform.environment[target.rpcEnvKey];
  if (override != null && override.isNotEmpty) return [override];
  return target.defaultRpcUrls;
}

Future<bool> _isSchemaRegistered({
  required List<String> rpcUrls,
  required String schemaRegistryAddress,
  required String schemaUid,
}) async {
  final schemaIdHex = schemaUid.toLowerCase().replaceFirst('0x', '');
  if (schemaIdHex.length != 64) {
    throw ArgumentError('Expected bytes32 schema UID, got: $schemaUid');
  }
  final callData = '$_getSchemaSelector$schemaIdHex';

  for (final rpcUrl in rpcUrls) {
    final resultHex = await _ethCall(
      rpcUrl: rpcUrl,
      to: schemaRegistryAddress,
      data: callData,
    );
    if (resultHex == null) continue;
    if (_parseSchemaUidFromGetSchemaResult(resultHex) != null) return true;
  }
  return false;
}

String? _parseSchemaUidFromGetSchemaResult(String resultHex) {
  final hex = resultHex.toLowerCase().replaceFirst('0x', '');
  if (hex.length < 128) return null;
  final uidWord = hex.substring(64, 128);
  if (uidWord.replaceAll('0', '').isEmpty) return null;
  return '0x$uidWord';
}

Future<String?> _ethCall({
  required String rpcUrl,
  required String to,
  required String data,
}) async {
  final payload = jsonEncode({
    'jsonrpc': '2.0',
    'method': 'eth_call',
    'params': [
      {'to': to, 'data': data},
      'latest',
    ],
    'id': 1,
  });

  final client = HttpClient();
  try {
    final request = await client.postUrl(Uri.parse(rpcUrl));
    request.headers.contentType = ContentType.json;
    request.write(payload);
    final response = await request.close();
    final body = await response.transform(utf8.decoder).join();
    if (response.statusCode != 200) return null;

    final decoded = jsonDecode(body) as Map<String, dynamic>;
    if (decoded.containsKey('error')) return null;
    final result = decoded['result'];
    if (result is! String || result.isEmpty || result == '0x') return null;
    return result;
  } catch (_) {
    return null;
  } finally {
    client.close(force: true);
  }
}

Future<String> _firstReachableRpc(List<String> rpcUrls) async {
  for (final rpcUrl in rpcUrls) {
    final client = HttpClient();
    try {
      final request = await client.postUrl(Uri.parse(rpcUrl));
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode({
        'jsonrpc': '2.0',
        'method': 'eth_blockNumber',
        'params': [],
        'id': 1,
      }));
      final response = await request.close();
      if (response.statusCode != 200) continue;
      final body = await response.transform(utf8.decoder).join();
      final decoded = jsonDecode(body) as Map<String, dynamic>;
      if (decoded['result'] != null) return rpcUrl;
    } catch (_) {
      continue;
    } finally {
      client.close(force: true);
    }
  }
  return rpcUrls.first;
}

Map<String, String> _loadDotEnv({String path = '.env'}) {
  final file = File(path);
  if (!file.existsSync()) return <String, String>{};

  final env = <String, String>{};
  for (final line in file.readAsLinesSync()) {
    final trimmed = line.trim();
    if (trimmed.isEmpty || trimmed.startsWith('#')) continue;

    final equalsIndex = trimmed.indexOf('=');
    if (equalsIndex < 0) continue;

    final key = trimmed.substring(0, equalsIndex).trim();
    var value = trimmed.substring(equalsIndex + 1).trim();

    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.substring(1, value.length - 1);
    }

    if (key.isNotEmpty && value.isNotEmpty) {
      env[key] = value;
    }
  }

  return env;
}

String _normalizePrivateKeyHex(String key) =>
    key.startsWith('0x') ? key.substring(2) : key;
