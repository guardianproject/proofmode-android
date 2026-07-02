import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:location_protocol_flutter/src/attestation_handler.dart'
    as handler;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Minimal valid LP payload map for use across tests.
Map<String, dynamic> _validPayload() => {
      'eventTimestamp': 1717200000,
      'srs': 'wgs84',
      'locationType': 'geojson-point',
      'location': '{"type":"Point","coordinates":[-73.9857,40.7484]}',
      'recipeType': ['camera'],
      'recipePayload': ['{}'],
      'mediaType': ['image/jpeg'],
      'mediaData': ['abc123'],
      'memo': 'test memo',
    };

/// A valid 65-byte hex signature (deterministic fake — v=27, r/s=zeros).
String validSignatureHex() {
  final r = '00' * 32;
  final s = '00' * 32;
  return '0x$r${s}1b';
}

const String kAttesterAddress = '0x1234567890123456789012345678901234567890';

/// Routes a method call directly through the handler, bypassing backgroundMain.
Future<dynamic> callHandler(String method, dynamic args) =>
    handler.handleCallForTesting(MethodCall(method, args));

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // =========================================================================
  // ping
  // =========================================================================

  group('ping', () {
    test('returns pong', () async {
      final result = await callHandler('ping', null);
      expect(result, 'pong');
    });
  });

  // =========================================================================
  // unknown method
  // =========================================================================

  group('unknown method', () {
    test('returns PlatformException with LP_UNKNOWN_METHOD', () async {
      await expectLater(
        callHandler('not-a-real-method', null),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_UNKNOWN_METHOD'),
        ),
      );
    });
  });

  // =========================================================================
  // build-eas-typed-data
  // =========================================================================

  group('build-eas-typed-data', () {
    test('valid payload returns non-empty JSON string', () async {
      final result = await callHandler(
        'build-eas-typed-data',
        {'payload': _validPayload()},
      );
      expect(result, isA<String>());
      expect((result as String).isNotEmpty, isTrue);
    });

    test('returned JSON is parseable as EIP-712 with expected top-level keys',
        () async {
      final result = await callHandler(
        'build-eas-typed-data',
        {'payload': _validPayload()},
      ) as String;
      final json = jsonDecode(result) as Map<String, dynamic>;
      expect(json.containsKey('domain'), isTrue);
      expect(json.containsKey('types'), isTrue);
      expect(json.containsKey('primaryType'), isTrue);
      expect(json.containsKey('message'), isTrue);
      expect(json['primaryType'], 'Attest');
    });

    test('message contains expected EAS attestation keys', () async {
      final result = await callHandler(
        'build-eas-typed-data',
        {'payload': _validPayload()},
      ) as String;
      final json = jsonDecode(result) as Map<String, dynamic>;
      final message = json['message'] as Map<String, dynamic>;
      for (final key in [
        'schema',
        'data',
        'salt',
        'version',
        'recipient',
        'time',
        'expirationTime',
        'revocable',
        'refUID',
      ]) {
        expect(message.containsKey(key), isTrue,
            reason: 'message missing key: $key');
      }
    });

    test('numeric fields in signing JSON are decimal strings', () async {
      final result = await callHandler(
        'build-eas-typed-data',
        {'payload': _validPayload()},
      ) as String;
      final json = jsonDecode(result) as Map<String, dynamic>;
      final message = json['message'] as Map<String, dynamic>;
      final domain = json['domain'] as Map<String, dynamic>;

      expect(message['time'], isA<String>(),
          reason: 'time must be a decimal string');
      expect(message['expirationTime'], isA<String>(),
          reason: 'expirationTime must be a decimal string');
      expect(message['version'], isA<String>(),
          reason: 'version must be a decimal string');
      expect(domain['chainId'], isA<String>(),
          reason: 'chainId must be a decimal string');
    });

    test('returned JSON includes passthrough _schemaUID and _saltHex', () async {
      final result = await callHandler(
        'build-eas-typed-data',
        {'payload': _validPayload()},
      ) as String;
      final json = jsonDecode(result) as Map<String, dynamic>;
      expect(json.containsKey('_schemaUID'), isTrue);
      expect(json.containsKey('_saltHex'), isTrue);
      expect((json['_schemaUID'] as String).startsWith('0x'), isTrue);
      expect((json['_saltHex'] as String).startsWith('0x'), isTrue);
    });

    test('missing required payload field → LP_MISSING_FIELD', () async {
      final payload = _validPayload()..remove('eventTimestamp');
      await expectLater(
        callHandler('build-eas-typed-data', {'payload': payload}),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_MISSING_FIELD'),
        ),
      );
    });

    test('missing payload key itself → LP_MISSING_FIELD', () async {
      await expectLater(
        callHandler('build-eas-typed-data', <String, dynamic>{}),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_MISSING_FIELD'),
        ),
      );
    });

    test('non-map payload value → LP_INVALID_PAYLOAD', () async {
      await expectLater(
        callHandler('build-eas-typed-data', {'payload': 42}),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_INVALID_PAYLOAD'),
        ),
      );
    });
  });

  // =========================================================================
  // create-offchain-attestation
  // =========================================================================

  group('create-offchain-attestation', () {
    Future<String> buildValidTypedData() async {
      return await callHandler(
            'build-eas-typed-data',
            {'payload': _validPayload()},
          ) as String;
    }

    test('valid typedData + valid signature → result map with required keys',
        () async {
      final typedData = await buildValidTypedData();
      final result = await callHandler(
        'create-offchain-attestation',
        {
          'typedData': typedData,
          'signature': validSignatureHex(),
          'attesterAddress': kAttesterAddress,
        },
      ) as Map;
      for (final key in [
        'uid',
        'schemaId',
        'attesterAddress',
        'timestamp',
        'offchainPayloadJson',
        'artifactPath',
      ]) {
        expect(result.containsKey(key), isTrue,
            reason: 'result missing key: $key');
      }
      expect(result['attesterAddress'], kAttesterAddress);
      expect(result['artifactPath'], '');
    });

    test('uid is deterministic given the same typedData and salt', () async {
      final typedData = await buildValidTypedData();
      final sig = validSignatureHex();

      final result1 = await callHandler(
        'create-offchain-attestation',
        {
          'typedData': typedData,
          'signature': sig,
          'attesterAddress': kAttesterAddress,
        },
      ) as Map;
      final result2 = await callHandler(
        'create-offchain-attestation',
        {
          'typedData': typedData,
          'signature': sig,
          'attesterAddress': kAttesterAddress,
        },
      ) as Map;

      expect(result1['uid'], result2['uid'],
          reason: 'UID must be deterministic for same typedData+salt');
    });

    test('invalid signature hex (wrong length) → LP_INVALID_SIGNATURE',
        () async {
      final typedData = await buildValidTypedData();
      await expectLater(
        callHandler(
          'create-offchain-attestation',
          {
            'typedData': typedData,
            'signature': '0xdeadbeef', // not 65 bytes
            'attesterAddress': kAttesterAddress,
          },
        ),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_INVALID_SIGNATURE'),
        ),
      );
    });

    test('missing signature key → LP_MISSING_FIELD', () async {
      final typedData = await buildValidTypedData();
      await expectLater(
        callHandler(
          'create-offchain-attestation',
          {
            'typedData': typedData,
            'attesterAddress': kAttesterAddress,
            // 'signature' omitted
          },
        ),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_MISSING_FIELD'),
        ),
      );
    });

    test('malformed typedData JSON string → LP_INVALID_PAYLOAD', () async {
      await expectLater(
        callHandler(
          'create-offchain-attestation',
          {
            'typedData': 'not valid json {{{',
            'signature': validSignatureHex(),
            'attesterAddress': kAttesterAddress,
          },
        ),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_INVALID_PAYLOAD'),
        ),
      );
    });

    test('missing typedData key → LP_MISSING_FIELD', () async {
      await expectLater(
        callHandler(
          'create-offchain-attestation',
          {
            'signature': validSignatureHex(),
            'attesterAddress': kAttesterAddress,
            // 'typedData' omitted
          },
        ),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_MISSING_FIELD'),
        ),
      );
    });

    test('missing attesterAddress → LP_MISSING_FIELD', () async {
      final typedData = await buildValidTypedData();
      await expectLater(
        callHandler(
          'create-offchain-attestation',
          {
            'typedData': typedData,
            'signature': validSignatureHex(),
            // 'attesterAddress' omitted
          },
        ),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_MISSING_FIELD'),
        ),
      );
    });
  });

  // =========================================================================
  // build-eas-onchain-data
  // =========================================================================

  group('build-eas-onchain-data', () {
    test('returns txData schemaId easAddress and schemaRegistryAddress for Sepolia', () async {
      final result = await callHandler(
        'build-eas-onchain-data',
        {
          'payload': _validPayload(),
          'chainId': 'eip155:11155111',
        },
      ) as Map;

      expect(result['txData'], isA<String>());
      expect((result['txData'] as String).startsWith('0xf17325e7'), isTrue);
      expect(result['schemaId'], startsWith('0x'));
      expect(
        result['easAddress'],
        '0xC2679fBD37d54388Ce493F1DB75320D236e1815e',
      );
      expect(
        result['schemaRegistryAddress'],
        '0x0a7E2Ff54e76B8E6659aedc9103FB21c038050D0',
      );
      expect(result['chainId'], 'eip155:11155111');
    });

    test('unsupported chainId → LP_UNSUPPORTED_CHAIN', () async {
      await expectLater(
        callHandler(
          'build-eas-onchain-data',
          {
            'payload': _validPayload(),
            'chainId': 'eip155:999999',
          },
        ),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_UNSUPPORTED_CHAIN'),
        ),
      );
    });
  });
}
