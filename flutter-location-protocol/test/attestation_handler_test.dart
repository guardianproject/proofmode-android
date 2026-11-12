import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:location_protocol/location_protocol.dart' show ChainConfig;
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

/// Args for `build-eas-typed-data`; the selected chain defaults to Sepolia
/// and `easVersion` to Sepolia's live version.
Map<String, dynamic> _typedDataArgs({
  Map<String, dynamic>? payload,
  String chainId = 'eip155:11155111',
  Object? easVersion = '0.26',
}) =>
    {
      'payload': payload ?? _validPayload(),
      'chainId': chainId,
      'easVersion': easVersion,
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

/// Expects [call] to throw a [PlatformException] carrying [code].
Future<void> _expectCode(Future<dynamic> call, String code, String label) =>
    expectLater(
      call,
      throwsA(isA<PlatformException>().having((e) => e.code, 'code', code)),
      reason: label,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

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
    test('returned JSON is parseable as EIP-712 with expected top-level keys',
        () async {
      final result = await callHandler(
        'build-eas-typed-data',
        _typedDataArgs(),
      ) as String;
      final json = jsonDecode(result) as Map<String, dynamic>;
      expect(json.containsKey('domain'), isTrue);
      expect(json.containsKey('types'), isTrue);
      expect(json.containsKey('primaryType'), isTrue);
      expect(json.containsKey('message'), isTrue);
      expect(json['primaryType'], 'Attest');

      final message = json['message'] as Map<String, dynamic>;
      final domain = json['domain'] as Map<String, dynamic>;
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

      expect(message['time'], isA<String>(),
          reason: 'time must be a decimal string');
      expect(message['expirationTime'], isA<String>(),
          reason: 'expirationTime must be a decimal string');
      expect(message['version'], isA<String>(),
          reason: 'version must be a decimal string');
      expect(domain['chainId'], isA<String>(),
          reason: 'chainId must be a decimal string');

      expect(json.containsKey('_schemaUID'), isTrue);
      expect(json.containsKey('_saltHex'), isTrue);
    });

    test('typed-data domain follows the chain and version', () async {
      final cases = <String, ({Map<String, dynamic> args, Map<String, String> domain})>{
        'Sepolia / 0.26': (
          args: _typedDataArgs(),
          domain: {
            'name': 'EAS Attestation',
            'version': '0.26',
            'chainId': '11155111',
            'verifyingContract': '0xC2679fBD37d54388Ce493F1DB75320D236e1815e',
          },
        ),
        'Ink Sepolia / 1.4.1-beta.3': (
          args: _typedDataArgs(
              chainId: 'eip155:763373', easVersion: '1.4.1-beta.3'),
          domain: {
            'name': 'EAS Attestation',
            'version': '1.4.1-beta.3',
            'chainId': '763373',
            'verifyingContract': '0x4200000000000000000000000000000000000021',
          },
        ),
      };
      for (final entry in cases.entries) {
        final result =
            await callHandler('build-eas-typed-data', entry.value.args)
                as String;
        final domain =
            (jsonDecode(result) as Map<String, dynamic>)['domain'] as Map;
        expect(domain, entry.value.domain, reason: entry.key);
      }
    });

    test('build-eas-typed-data rejects invalid args', () async {
      final cases = <String, ({dynamic args, String code})>{
        'missing eventTimestamp': (
          args: _typedDataArgs(
              payload: _validPayload()..remove('eventTimestamp')),
          code: 'LP_MISSING_FIELD',
        ),
        'missing payload key itself': (
          args: <String, dynamic>{},
          code: 'LP_MISSING_FIELD',
        ),
        'non-map payload value': (
          args: {'payload': 42},
          code: 'LP_INVALID_PAYLOAD',
        ),
        'easVersion absent': (
          args: _typedDataArgs()..remove('easVersion'),
          code: 'LP_INVALID_PAYLOAD',
        ),
        'easVersion non-string': (
          args: _typedDataArgs(easVersion: 42),
          code: 'LP_INVALID_PAYLOAD',
        ),
        'easVersion blank': (
          args: _typedDataArgs(easVersion: '   '),
          code: 'LP_INVALID_PAYLOAD',
        ),
      };
      for (final entry in cases.entries) {
        await _expectCode(
          callHandler('build-eas-typed-data', entry.value.args),
          entry.value.code,
          '${entry.key}: ${entry.value.args}',
        );
      }
    });

    test('typed-data unsupported chain', () async {
      await expectLater(
        callHandler(
            'build-eas-typed-data', _typedDataArgs(chainId: 'eip155:999999')),
        throwsA(
          isA<PlatformException>()
              .having((e) => e.code, 'code', 'LP_UNSUPPORTED_CHAIN'),
        ),
      );
    });
  });

  // =========================================================================
  // get-eas-chain-params
  // =========================================================================

  group('get-eas-chain-params', () {
    test('returns the EAS address and table version', () async {
      final sepolia = await callHandler(
        'get-eas-chain-params',
        {'chainId': 'eip155:11155111'},
      ) as Map;
      expect(sepolia, {
        'easAddress': '0xC2679fBD37d54388Ce493F1DB75320D236e1815e',
        'easVersion': '0.26',
      });

      final ink = ChainConfig.forChainId(57073)!;
      final inkResult = await callHandler(
        'get-eas-chain-params',
        {'chainId': 'eip155:57073'},
      ) as Map;
      expect(inkResult, {'easAddress': ink.eas, 'easVersion': ink.easVersion});
    });

    test('get-eas-chain-params rejects invalid chainId', () async {
      final cases = <String, ({dynamic args, String code})>{
        'unsupported chain': (
          args: {'chainId': 'eip155:999999'},
          code: 'LP_UNSUPPORTED_CHAIN',
        ),
        'chainId absent': (
          args: <String, dynamic>{},
          code: 'LP_INVALID_PAYLOAD',
        ),
        'chainId is an int': (
          args: {'chainId': 11155111},
          code: 'LP_INVALID_PAYLOAD',
        ),
        'args null': (args: null, code: 'LP_INVALID_PAYLOAD'),
      };
      for (final entry in cases.entries) {
        await _expectCode(
          callHandler('get-eas-chain-params', entry.value.args),
          entry.value.code,
          '${entry.key}: ${entry.value.args}',
        );
      }
    });
  });

  // =========================================================================
  // create-offchain-attestation
  // =========================================================================

  group('create-offchain-attestation', () {
    Future<String> buildValidTypedData() async {
      return await callHandler(
            'build-eas-typed-data',
            _typedDataArgs(),
          ) as String;
    }

    test('valid typedData + valid signature → result map with required keys',
        () async {
      final typedData = await buildValidTypedData();
      final args = {
        'typedData': typedData,
        'signature': validSignatureHex(),
        'attesterAddress': kAttesterAddress,
      };
      final result = await callHandler('create-offchain-attestation', args)
          as Map;
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

      // A fresh salt or clock read in the handler would change the uid.
      final again = await callHandler('create-offchain-attestation', args)
          as Map;
      expect(result['uid'], again['uid'],
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

    test('create-offchain-attestation rejects a missing key', () async {
      final typedData = await buildValidTypedData();
      for (final omitted in ['signature', 'typedData', 'attesterAddress']) {
        final args = <String, dynamic>{
          'typedData': typedData,
          'signature': validSignatureHex(),
          'attesterAddress': kAttesterAddress,
        }..remove(omitted);
        await _expectCode(
          callHandler('create-offchain-attestation', args),
          'LP_MISSING_FIELD',
          'omitted: $omitted',
        );
      }
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
