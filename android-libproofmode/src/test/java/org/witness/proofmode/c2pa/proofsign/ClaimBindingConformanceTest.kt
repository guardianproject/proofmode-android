package org.witness.proofmode.c2pa.proofsign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Cross-platform `claim_binding` conformance for the Android client.
 *
 * One canonical primitive must back the Standard Play Integrity `requestHash`
 * and the Android device-request signature, byte-identical with the Rust
 * server and the iOS client. This pins [ClaimBinding] against the SHARED,
 * cross-language known-answer vector vendored at
 * `src/test/resources/claim_binding_vectors.json` (a verbatim copy of the
 * canonical fixture also vendored by proofsign-rust). If the client's
 * derivation drifts, the pinned values stop reproducing and this fails.
 *
 * Mirrors proofsign-rust `tests/claim_binding_conformance_test.rs`.
 */
class ClaimBindingConformanceTest {

    private data class Fixture(
        val bytes: ByteArray,
        val bindingBytesUtf8: String,
        val claimBinding: String,
        val deviceId: String,
        val timestampMillis: Long,
        val deviceSignaturePayload: String,
    )

    private fun hexDecode(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    private fun fixture(): Fixture {
        val text = javaClass.classLoader!!
            .getResourceAsStream("claim_binding_vectors.json")!!
            .bufferedReader().use { it.readText() }
        val o = Json.parseToJsonElement(text).jsonObject
        fun str(k: String) = o[k]!!.jsonPrimitive.content
        return Fixture(
            bytes = hexDecode(str("binding_bytes_hex")),
            bindingBytesUtf8 = str("binding_bytes_utf8"),
            claimBinding = str("claim_binding"),
            deviceId = str("device_id"),
            timestampMillis = str("timestamp_millis").toLong(),
            deviceSignaturePayload = str("device_signature_payload"),
        )
    }

    /** The live client primitive must still produce the value every other consumer pins. */
    @Test
    fun clientPrimitiveReproducesThePinnedFixture() {
        val f = fixture()
        assertArrayEqualsMsg(
            f.bindingBytesUtf8.toByteArray(Charsets.UTF_8), f.bytes,
            "fixture hex and utf8 must describe the same bytes",
        )
        assertEquals(f.claimBinding, ClaimBinding.claimBinding(f.bytes))
    }

    /** Full c2pa wire path: claim is standard base64; binding is over the decoded bytes. */
    @Test
    fun c2paWirePathBindsTheDecodedClaim() {
        val f = fixture()
        val wireClaim = Base64.getEncoder().encodeToString(f.bytes) // what the client sends
        val decoded = Base64.getDecoder().decode(wireClaim)
        assertEquals(f.claimBinding, ClaimBinding.claimBinding(decoded))
    }

    /** The device-request signing input must match the pinned payload exactly. */
    @Test
    fun deviceSignaturePayloadMatchesFixture() {
        val f = fixture()
        assertEquals(
            f.deviceSignaturePayload,
            ClaimBinding.deviceRequestSigningInput(f.deviceId, f.timestampMillis, f.claimBinding),
        )
        // Reconstructed from raw bytes through the canonical primitive.
        assertEquals(
            f.deviceSignaturePayload,
            ClaimBinding.deviceRequestSigningInput(
                f.deviceId, f.timestampMillis, ClaimBinding.claimBinding(f.bytes),
            ),
        )
    }

    /** Regression guard: the old asymmetry is gone — the base64 string form must NOT match. */
    @Test
    fun stringFormDoesNotReproduceTheBinding() {
        val f = fixture()
        val b64StringForm = Base64.getEncoder().encodeToString(f.bytes)
        assertTrue(
            "binding over the base64 string form must differ from the canonical binding",
            ClaimBinding.claimBinding(b64StringForm.toByteArray(Charsets.UTF_8)) != f.claimBinding,
        )
    }

    private fun assertArrayEqualsMsg(expected: ByteArray, actual: ByteArray, msg: String) =
        assertTrue(msg, expected.contentEquals(actual))
}
