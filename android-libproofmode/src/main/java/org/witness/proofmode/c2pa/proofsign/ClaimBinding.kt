package org.witness.proofmode.c2pa.proofsign

import java.security.MessageDigest
import java.util.Base64

/**
 * Canonical claim-binding primitives, mirroring the Rust crate
 * `proofmode::sign::{claim_binding, device_request_signing_input}`.
 *
 * These MUST reproduce the shared cross-language known-answer vector
 * `claim_binding_vectors.json` (also vendored by proofsign-rust and
 * proofmode-ios). Changing either function without updating that fixture —
 * and every consumer — in lockstep is exactly the drift this guards against
 * (see ClaimBindingConformanceTest).
 *
 * One rule for both payload types:
 *  - c2pa: `bindingBytes` = the base64-decoded `claim` bytes
 *  - csr:  `bindingBytes` = the PEM string's UTF-8 bytes (no decode)
 */
internal object ClaimBinding {

    /** `Base64Std(SHA-256(bindingBytes))` — standard alphabet, `=` padded. */
    fun claimBinding(bindingBytes: ByteArray): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(bindingBytes),
        )

    /**
     * The device-request signing input: the ASCII string
     * `deviceId|timestampMillis|claimBinding` (literal `|`, decimal millis).
     * The device key signs the UTF-8 bytes of this string.
     */
    fun deviceRequestSigningInput(
        deviceId: String,
        timestampMillis: Long,
        claimBinding: String,
    ): String = "$deviceId|$timestampMillis|$claimBinding"
}
