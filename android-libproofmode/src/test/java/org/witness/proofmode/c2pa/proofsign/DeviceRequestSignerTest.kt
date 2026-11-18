package org.witness.proofmode.c2pa.proofsign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The device-key headers for ProofSign requests outside a JSON body: a signature over
 * `deviceId|timestamp|claimBinding(body)` with a fresh timestamp per request, and
 * registration that counts only for the server it was made with. Uses a software P-256
 * key; the Android Keystore half lives in DeviceKey and needs a device.
 */
class DeviceRequestSignerTest {

    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private val softwareSign: (ByteArray) -> String = { data ->
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(data)
                sign()
            },
        )
    }

    private fun verifies(signature: String, input: String): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public)
            update(input.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signature))
        }

    @Test
    fun signsDeviceIdTimestampAndBodyBinding() {
        val body = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x01)
        val signer = deviceRequestSigner("dev-1", { 1_700_000_000_000L }, softwareSign)

        val headers = signer.headersFor(body)

        assertEquals("dev-1", headers.deviceId)
        assertEquals(1_700_000_000_000L, headers.timestampMillis)
        val binding = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
        assertTrue(verifies(headers.signature, "dev-1|1700000000000|$binding"))
    }

    @Test
    fun clockIsReadPerCall() {
        var now = 1_700_000_000_000L
        val signer = deviceRequestSigner("dev-1", { now++ }, softwareSign)

        val first = signer.headersFor(byteArrayOf(1))
        val second = signer.headersFor(byteArrayOf(1))

        assertNotEquals(first.timestampMillis, second.timestampMillis)
    }

    @Test
    fun registrationIsPerServer() {
        assertTrue(registeredWith(registered = true, lastServerUrl = "https://a", serverUrl = "https://a"))
        assertFalse(registeredWith(registered = true, lastServerUrl = "https://a", serverUrl = "https://b"))
        assertFalse(registeredWith(registered = false, lastServerUrl = "https://a", serverUrl = "https://a"))
        assertFalse(registeredWith(registered = true, lastServerUrl = null, serverUrl = "https://a"))
    }
}
