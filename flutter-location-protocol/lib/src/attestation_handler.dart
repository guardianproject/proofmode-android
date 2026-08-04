import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:location_protocol/location_protocol.dart';

import 'bridge_channels.dart';

// ---------------------------------------------------------------------------
// Bridge runtime state
// ---------------------------------------------------------------------------

const MethodChannel _methodChannel = MethodChannel(channelLocationProtocol);
final Completer<void> _isolateKeepAlive = Completer<void>();

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/// Headless entry point — registered via @pragma('vm:entry-point') in main.dart.
///
/// 1. Registers the method-call handler.
/// 2. Fires `bridge/ready` liveness signal to Kotlin (best-effort).
/// 3. Blocks on keepalive future until Kotlin sends `bridge/shutdown`.
Future<void> backgroundMain() async {
  WidgetsFlutterBinding.ensureInitialized();

  _methodChannel.setMethodCallHandler(_handleCall);

  debugPrint('[LP] method channel handler registered');
  unawaited(_signalReady());
  await _isolateKeepAlive.future;
}

// ---------------------------------------------------------------------------
// Liveness handshake
// ---------------------------------------------------------------------------

Future<void> _signalReady() async {
  // Retry with backoff — the Kotlin method channel handler may not be
  // registered yet when the Dart isolate boots (race condition between
  // engine.createAndRunEngine returning and FlutterEngineBridge init).
  const maxAttempts = 20;
  const retryDelay = Duration(milliseconds: 250);

  for (var attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      await _methodChannel.invokeMethod<dynamic>('bridge/ready', null);
      debugPrint('[LP] bridge/ready acknowledged by Kotlin (attempt $attempt)');
      return;
    } catch (e) {
      if (attempt < maxAttempts) {
        debugPrint('[LP] bridge/ready attempt $attempt failed, retrying...');
        await Future<void>.delayed(retryDelay);
      } else {
        debugPrint('[LP] bridge/ready failed after $maxAttempts attempts: $e');
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Method call dispatcher
// ---------------------------------------------------------------------------

Future<dynamic> _handleCall(MethodCall call) async {
  try {
    return await _dispatch(call);
  } on PlatformException {
    rethrow;
  } catch (e) {
    throw PlatformException(code: 'LP_ERROR', message: e.toString());
  }
}

Future<dynamic> _dispatch(MethodCall call) async {
  switch (call.method) {
    case 'ping':
      return 'pong';

    case 'build-eas-typed-data':
      final args =
          (call.arguments as Map?)?.cast<Object?, Object?>() ?? const {};
      return _buildEasTypedData(_coerceStringMap(args));

    case 'create-offchain-attestation':
      final args =
          (call.arguments as Map?)?.cast<Object?, Object?>() ?? const {};
      return _createOffchainAttestation(_coerceStringMap(args));

    case 'build-eas-onchain-data':
      final args =
          (call.arguments as Map?)?.cast<Object?, Object?>() ?? const {};
      return _buildEasOnchainData(_coerceStringMap(args));

    case 'bridge/shutdown':
      if (!_isolateKeepAlive.isCompleted) {
        _isolateKeepAlive.complete();
      }
      return null;

    default:
      throw PlatformException(
        code: 'LP_UNKNOWN_METHOD',
        message: 'Unknown method: ${call.method}',
      );
  }
}

// ---------------------------------------------------------------------------
// build-eas-typed-data
// ---------------------------------------------------------------------------

/// Builds EAS EIP-712 typed-data JSON from an LP payload map.
///
/// Input: root map with key `payload` containing the LP fields.
/// Output: JSON string representing the full EIP-712 typed-data structure,
///         including `salt` and `schemaUID` as passthrough fields in the
///         `message` so `create-offchain-attestation` can use them for
///         UID computation without re-deriving.
///
/// The EIP-712 JSON structure mirrors the canonical Attest typed-data layout
/// defined by EAS v1.3.0 (Sepolia):
///   - `chainId` in domain is a decimal string
///   - `time`, `expirationTime`, `version` in message are decimal strings
Future<String> _buildEasTypedData(Map<String, dynamic> root) async {
  // ── 1. Extract payload sub-map ─────────────────────────────────────────
  final raw = root['payload'];
  if (raw == null) {
    throw PlatformException(
      code: 'LP_MISSING_FIELD',
      message: 'Missing required field: payload',
    );
  }

  final Map<String, dynamic> payload;
  if (raw is String) {
    // Kotlin may serialize the payload as a JSON string
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        throw const FormatException('payload JSON is not an object');
      }
      payload = decoded.cast<String, dynamic>();
    } catch (e) {
      throw PlatformException(
        code: 'LP_INVALID_PAYLOAD',
        message: 'payload is not valid JSON: $e',
      );
    }
  } else if (raw is Map) {
    payload = raw.map((k, v) => MapEntry(k.toString(), v));
  } else {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message: 'payload must be a map or JSON string',
    );
  }

  // ── 2. Require mandatory fields ────────────────────────────────────────
  const requiredFields = [
    'eventTimestamp',
    'srs',
    'locationType',
    'location',
    'recipeType',
    'recipePayload',
    'mediaType',
    'mediaData',
  ];
  for (final field in requiredFields) {
    if (!payload.containsKey(field)) {
      throw PlatformException(
        code: 'LP_MISSING_FIELD',
        message: 'Missing required payload field: $field',
      );
    }
  }

  // ── 3. Build LP primitives ─────────────────────────────────────────────
  final LPPayload lpPayload;
  try {
    lpPayload = LPPayload(
      lpVersion: '1.0.0',
      srs: _normalizeSrs(payload['srs']),
      locationType: (payload['locationType'] as String?) ?? 'geojson-point',
      location: _decodeLocation(payload['location']),
      validateLocation: false,
    );
  } catch (e) {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message: 'Failed to construct LPPayload: $e',
    );
  }

  final schema = SchemaDefinition(fields: [
    SchemaField(type: 'uint256', name: 'event_timestamp'),
    SchemaField(type: 'string', name: 'recipe_type'),
    SchemaField(type: 'string', name: 'recipe_payload'),
    SchemaField(type: 'string', name: 'media_type'),
    SchemaField(type: 'string', name: 'media_data'),
    SchemaField(type: 'string', name: 'memo'),
  ]);

  final userData = <String, dynamic>{
    'event_timestamp': _readInt(payload['eventTimestamp']),
    'recipe_type': jsonEncode(_readStringList(payload['recipeType'])),
    'recipe_payload': jsonEncode(_readStringList(payload['recipePayload'])),
    'media_type': jsonEncode(_readStringList(payload['mediaType'])),
    'media_data': jsonEncode(_readStringList(payload['mediaData'])),
    'memo': (payload['memo'] as String?) ?? '',
  };

  // ── 4. ABI-encode and compute schema UID ──────────────────────────────
  final Uint8List encodedData;
  try {
    encodedData = AbiEncoder.encode(
      schema: schema,
      lpPayload: lpPayload,
      userData: userData,
    );
  } catch (e) {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message: 'ABI encoding failed: $e',
    );
  }

  final schemaUID = SchemaUID.compute(schema);

  // ── 5. Build canonical domain + types + message ───────────────────────
  const chainId = 11155111; // Sepolia
  final chainAddresses = ChainConfig.forChainId(chainId)!;

  // Domain (canonical — chainId stored as int for attestation object)
  final domain = <String, dynamic>{
    'name': EASConstants.eip712DomainName,
    'version': chainAddresses.easVersion,
    'chainId': chainId,
    'verifyingContract': chainAddresses.eas,
  };

  // Canonical EIP-712 types for EAS Attest primary type
  final types = <String, dynamic>{
    'EIP712Domain': [
      {'name': 'name', 'type': 'string'},
      {'name': 'version', 'type': 'string'},
      {'name': 'chainId', 'type': 'uint256'},
      {'name': 'verifyingContract', 'type': 'address'},
    ],
    'Attest': [
      {'name': 'version', 'type': 'uint16'},
      {'name': 'schema', 'type': 'bytes32'},
      {'name': 'recipient', 'type': 'address'},
      {'name': 'time', 'type': 'uint64'},
      {'name': 'expirationTime', 'type': 'uint64'},
      {'name': 'revocable', 'type': 'bool'},
      {'name': 'refUID', 'type': 'bytes32'},
      {'name': 'data', 'type': 'bytes'},
      {'name': 'salt', 'type': 'bytes32'},
    ],
  };

  // Salt (random, 32 bytes)
  final saltBytes = EASConstants.generateSalt();
  final saltHex = EASConstants.saltToHex(saltBytes);

  // Time
  final now = BigInt.from(DateTime.now().millisecondsSinceEpoch ~/ 1000);
  final expirationTime = BigInt.zero;
  const recipient = '0x0000000000000000000000000000000000000000';
  const refUID =
      '0x0000000000000000000000000000000000000000000000000000000000000000';

  // Canonical message (BigInt fields for attestation object)
  final message = <String, dynamic>{
    'version': EASConstants.attestationVersion,
    'schema': schemaUID,
    'recipient': recipient,
    'time': now,
    'expirationTime': expirationTime,
    'revocable': schema.revocable,
    'refUID': refUID,
    'data': '0x${_toHexString(encodedData)}',
    'salt': saltHex,
  };

  // ── 6. Build signing-compatible JSON (decimal strings for numeric types)
  //       Mirrors canonical EAS Attest typed-data structure (decimal string encoding)
  final signDomain = Map<String, dynamic>.from(domain);
  signDomain['chainId'] = chainId.toString();

  final signMessage = Map<String, dynamic>.from(message);
  signMessage['time'] = now.toString();
  signMessage['expirationTime'] = expirationTime.toString();
  signMessage['version'] = EASConstants.attestationVersion.toString();

  // ── 7. Include passthrough fields for create-offchain-attestation ──────
  final typedDataJson = <String, dynamic>{
    'types': types,
    'primaryType': 'Attest',
    'domain': signDomain,
    'message': signMessage,
    // Passthrough fields — not part of EIP-712 structure itself
    '_schemaUID': schemaUID,
    '_encodedData': '0x${_toHexString(encodedData)}',
    '_saltHex': saltHex,
    '_recipient': recipient,
    '_time': now.toString(),
    '_expirationTime': expirationTime.toString(),
    '_revocable': schema.revocable,
    '_refUID': refUID,
  };

  return jsonEncode(typedDataJson);
}

// ---------------------------------------------------------------------------
// create-offchain-attestation
// ---------------------------------------------------------------------------

/// Assembles a SignedOffchainAttestation from pre-built typed-data and Privy signature.
///
/// Input keys:
///   - `typedData`       : JSON string returned by `build-eas-typed-data`
///   - `signature`       : 65-byte hex from Privy `eth_signTypedData_v4`
///   - `attesterAddress` : Ethereum address of the Privy wallet signer
///
/// Output map keys: `uid`, `schemaId`, `attesterAddress`, `timestamp`,
///                   `offchainPayloadJson`, `artifactPath`
Future<Map<String, dynamic>> _createOffchainAttestation(
  Map<String, dynamic> args,
) async {
  // ── 1. Extract required fields ─────────────────────────────────────────
  final typedDataJsonStr = args['typedData'];
  if (typedDataJsonStr == null) {
    throw PlatformException(
      code: 'LP_MISSING_FIELD',
      message: 'Missing required field: typedData',
    );
  }
  final signatureHex = args['signature'];
  if (signatureHex == null) {
    throw PlatformException(
      code: 'LP_MISSING_FIELD',
      message: 'Missing required field: signature',
    );
  }
  final attesterAddress = args['attesterAddress'];
  if (attesterAddress == null) {
    throw PlatformException(
      code: 'LP_MISSING_FIELD',
      message: 'Missing required field: attesterAddress',
    );
  }

  // ── 2. Parse typedData JSON ────────────────────────────────────────────
  final Map<String, dynamic> typedData;
  try {
    final decoded = jsonDecode(typedDataJsonStr as String);
    if (decoded is! Map) {
      throw const FormatException('typedData JSON is not an object');
    }
    typedData = decoded.cast<String, dynamic>();
  } catch (e) {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message: 'Failed to parse typedData JSON: $e',
    );
  }

  // ── 3. Extract domain/types/message for SignedOffchainAttestation ──────
  final domain = (typedData['domain'] as Map?)?.cast<String, dynamic>();
  final types = (typedData['types'] as Map?)?.cast<String, dynamic>();
  final signMessage =
      (typedData['message'] as Map?)?.cast<String, dynamic>();

  if (domain == null || types == null || signMessage == null) {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message:
          'typedData must contain domain, types, and message keys',
    );
  }

  // ── 4. Reconstruct canonical domain (chainId as int) ──────────────────
  final canonicalDomain = Map<String, dynamic>.from(domain);
  final chainIdRaw = domain['chainId'];
  canonicalDomain['chainId'] = chainIdRaw is int
      ? chainIdRaw
      : int.parse(chainIdRaw.toString());

  // ── 5. Reconstruct canonical message (BigInt for numeric fields) ───────
  final canonicalMessage = Map<String, dynamic>.from(signMessage);
  final timeStr = signMessage['time']?.toString() ?? '0';
  final expStr = signMessage['expirationTime']?.toString() ?? '0';
  final verStr = signMessage['version']?.toString() ?? '2';
  canonicalMessage['time'] = BigInt.parse(timeStr);
  canonicalMessage['expirationTime'] = BigInt.parse(expStr);
  canonicalMessage['version'] = int.parse(verStr);

  // ── 6. Parse passthrough fields for UID computation ───────────────────
  final schemaUID = (typedData['_schemaUID'] as String?) ??
      (signMessage['schema'] as String? ?? '');
  final saltHex = (typedData['_saltHex'] as String?) ??
      (signMessage['salt'] as String? ?? '');
  final recipient = (typedData['_recipient'] as String?) ??
      (signMessage['recipient'] as String? ??
          '0x0000000000000000000000000000000000000000');
  final revocable = (typedData['_revocable'] as bool?) ??
      (signMessage['revocable'] as bool? ?? false);
  final refUID = (typedData['_refUID'] as String?) ??
      (signMessage['refUID'] as String? ??
          '0x0000000000000000000000000000000000000000000000000000000000000000');
  final dataHex = (typedData['_encodedData'] as String?) ??
      (signMessage['data'] as String? ?? '0x');

  final now = BigInt.parse(timeStr);
  final exp = BigInt.parse(expStr);

  // ── 7. Parse Privy signature ───────────────────────────────────────────
  final EIP712Signature eip712Sig;
  try {
    eip712Sig = EIP712Signature.fromHex(signatureHex as String);
  } on ArgumentError catch (e) {
    throw PlatformException(
      code: 'LP_INVALID_SIGNATURE',
      message: 'Invalid signature hex: $e',
    );
  }

  // Normalize v to 27/28
  final normalizedV = eip712Sig.v < 27 ? eip712Sig.v + 27 : eip712Sig.v;
  final normalizedSig =
      EIP712Signature(v: normalizedV, r: eip712Sig.r, s: eip712Sig.s);

  // ── 8. Decode salt and data bytes ─────────────────────────────────────
  final Uint8List saltBytes;
  try {
    final saltHexClean =
        saltHex.startsWith('0x') ? saltHex.substring(2) : saltHex;
    saltBytes = Uint8List.fromList(_hexToBytes(saltHexClean));
  } catch (e) {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message: 'Failed to decode salt: $e',
    );
  }

  final Uint8List dataBytes;
  try {
    final dataHexClean =
        dataHex.startsWith('0x') ? dataHex.substring(2) : dataHex;
    dataBytes = dataHexClean.isEmpty
        ? Uint8List(0)
        : Uint8List.fromList(_hexToBytes(dataHexClean));
  } catch (e) {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message: 'Failed to decode data bytes: $e',
    );
  }

  // ── 9. Compute UID ─────────────────────────────────────────────────────
  final uid = OffchainSigner.computeOffchainUID(
    schemaUID: schemaUID,
    recipient: recipient,
    time: now,
    expirationTime: exp,
    revocable: revocable,
    refUID: refUID,
    data: dataBytes,
    salt: saltBytes,
  );

  // ── 10. Build SignedOffchainAttestation ────────────────────────────────
  final signed = SignedOffchainAttestation(
    signer: attesterAddress as String,
    domain: canonicalDomain,
    primaryType: 'Attest',
    types: types,
    message: canonicalMessage,
    signature: normalizedSig,
    uid: uid,
  );

  final signedJson = _toBridgeEncodable(signed.toJson());
  final offchainPayloadJson = jsonEncode(signedJson);

  return _toBridgeEncodable(<String, dynamic>{
    'uid': uid,
    'schemaId': schemaUID,
    'attesterAddress': attesterAddress,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    'offchainPayloadJson': offchainPayloadJson,
    'artifactPath': '',
  }) as Map<String, dynamic>;
}

// ---------------------------------------------------------------------------
// build-eas-onchain-data
// ---------------------------------------------------------------------------

Future<Map<String, dynamic>> _buildEasOnchainData(Map<String, dynamic> args) async {
  // ── 1. Extract required fields ─────────────────────────────────────────
  final chainIdStr = args['chainId'] as String?;
  if (chainIdStr == null) {
    throw PlatformException(
      code: 'LP_MISSING_FIELD',
      message: 'Missing required field: chainId',
    );
  }
  final raw = args['payload'];
  if (raw == null) {
    throw PlatformException(
      code: 'LP_MISSING_FIELD',
      message: 'Missing required field: payload',
    );
  }

  final Map<String, dynamic> payload;
  if (raw is String) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        throw const FormatException('payload JSON is not an object');
      }
      payload = decoded.cast<String, dynamic>();
    } catch (e) {
      throw PlatformException(
        code: 'LP_INVALID_PAYLOAD',
        message: 'payload is not valid JSON: $e',
      );
    }
  } else if (raw is Map) {
    payload = raw.map((k, v) => MapEntry(k.toString(), v));
  } else {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message: 'payload must be a map or JSON string',
    );
  }

  // ── 2. Parse ChainID ───────────────────────────────────────────────────
  final numericChainId = int.parse(chainIdStr.replaceAll('eip155:', ''));
  final chainAddresses = ChainConfig.forChainId(numericChainId);
  if (chainAddresses == null) {
    throw PlatformException(
      code: 'LP_UNSUPPORTED_CHAIN',
      message: 'Unsupported chain ID: $numericChainId',
    );
  }

  // ── 3. Build LP primitives ─────────────────────────────────────────────
  final LPPayload lpPayload;
  try {
    lpPayload = LPPayload(
      lpVersion: '1.0.0',
      srs: _normalizeSrs(payload['srs']),
      locationType: (payload['locationType'] as String?) ?? 'geojson-point',
      location: _decodeLocation(payload['location']),
      validateLocation: false,
    );
  } catch (e) {
    throw PlatformException(
      code: 'LP_INVALID_PAYLOAD',
      message: 'Failed to construct LPPayload: $e',
    );
  }

  final schema = SchemaDefinition(fields: [
    SchemaField(type: 'uint256', name: 'event_timestamp'),
    SchemaField(type: 'string', name: 'recipe_type'),
    SchemaField(type: 'string', name: 'recipe_payload'),
    SchemaField(type: 'string', name: 'media_type'),
    SchemaField(type: 'string', name: 'media_data'),
    SchemaField(type: 'string', name: 'memo'),
  ]);

  final userData = <String, dynamic>{
    'event_timestamp': _readInt(payload['eventTimestamp']),
    'recipe_type': jsonEncode(_readStringList(payload['recipeType'])),
    'recipe_payload': jsonEncode(_readStringList(payload['recipePayload'])),
    'media_type': jsonEncode(_readStringList(payload['mediaType'])),
    'media_data': jsonEncode(_readStringList(payload['mediaData'])),
    'memo': (payload['memo'] as String?) ?? '',
  };

  // ── 4. Call EASClient to build transaction data ────────────────────────
  final schemaUID = SchemaUID.compute(schema);
  final txDataBytes = EASClient.buildAttestCallData(
    schema: schema,
    lpPayload: lpPayload,
    userData: userData,
  );

  return <String, dynamic>{
    'txData': '0x${_toHexString(txDataBytes)}',
    'schemaId': schemaUID,
    'easAddress': chainAddresses.eas,
    'schemaRegistryAddress': chainAddresses.schemaRegistry,
    'chainId': chainIdStr,
  };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Converts any dynamic value to a bridge-serialisable form (JSON safe).
dynamic _toBridgeEncodable(dynamic value) {
  if (value == null ||
      value is bool ||
      value is int ||
      value is double ||
      value is String) {
    return value;
  }
  if (value is BigInt) {
    return value.toString();
  }
  if (value is DateTime) {
    return value.toIso8601String();
  }
  if (value is List) {
    return value.map(_toBridgeEncodable).toList(growable: false);
  }
  if (value is Map) {
    final result = <String, dynamic>{};
    for (final entry in value.entries) {
      result[entry.key.toString()] = _toBridgeEncodable(entry.value);
    }
    return result;
  }
  return value.toString();
}

Map<String, dynamic> _coerceStringMap(Map<Object?, Object?> input) {
  final result = <String, dynamic>{};
  for (final entry in input.entries) {
    if (entry.key != null) {
      result[entry.key.toString()] = entry.value;
    }
  }
  return result;
}

String _normalizeSrs(dynamic value) {
  final srs = value?.toString() ?? '';
  if (srs.contains(':')) return srs;
  if (srs.toLowerCase() == 'wgs84') {
    return 'urn:ogc:def:crs:EPSG::4326';
  }
  return 'urn:ogc:def:crs:EPSG::4326';
}

dynamic _decodeLocation(dynamic value) {
  if (value is String) {
    try {
      return jsonDecode(value);
    } catch (_) {
      return value;
    }
  }
  return value;
}

int _readInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

List<String> _readStringList(dynamic value) {
  if (value is List) {
    return value.map((e) => e.toString()).toList();
  }
  return const <String>[];
}

String _toHexString(Uint8List bytes) {
  final buf = StringBuffer();
  for (final byte in bytes) {
    buf.write(byte.toRadixString(16).padLeft(2, '0'));
  }
  return buf.toString();
}

List<int> _hexToBytes(String hex) {
  final result = <int>[];
  for (var i = 0; i < hex.length; i += 2) {
    result.add(int.parse(hex.substring(i, i + 2), radix: 16));
  }
  return result;
}

// ---------------------------------------------------------------------------
// Test shim (visible only in test scope — do NOT call from production code)
// ---------------------------------------------------------------------------

/// Routes a [MethodCall] through the handler dispatcher.
///
/// Exposed so unit tests can invoke handler logic without calling
/// [backgroundMain] (which blocks forever on the keepalive future).
/// Production callers should never reference this function directly.
Future<dynamic> handleCallForTesting(MethodCall call) => _handleCall(call);
