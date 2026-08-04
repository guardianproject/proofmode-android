import 'src/attestation_handler.dart' as handler;

// Module entrypoint — bridge handshake and lifecycle live in the handler.
Future<void> main() => handler.backgroundMain();
