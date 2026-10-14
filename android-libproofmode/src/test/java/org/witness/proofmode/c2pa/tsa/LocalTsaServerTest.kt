package org.witness.proofmode.c2pa.tsa

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cmp.PKIFailureInfo
import org.bouncycastle.asn1.cmp.PKIStatus
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TimeStampRequest
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponse
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * End-to-end coverage of the on-device RFC 3161 TSA: a real HTTP POST over
 * loopback, verified the way a C2PA validator would verify the countersignature.
 * Uses a software key; the Android Keystore half of [LocalTsa] needs a device.
 */
class LocalTsaServerTest {

    private lateinit var keyPair: KeyPair
    private lateinit var cert: X509Certificate
    private lateinit var server: LocalTsaServer

    @Before
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        cert = LocalTsa.createTsaCertificate(keyPair.private, keyPair.public)
        server = LocalTsaServer(TimeStampResponder(keyPair.private, cert))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun request(policy: ASN1ObjectIdentifier? = null): TimeStampRequest {
        val digest = MessageDigest.getInstance("SHA-256").digest("c2pa claim signature".toByteArray())
        return TimeStampRequestGenerator().apply {
            setCertReq(true)
            policy?.let { setReqPolicy(it) }
        }.generate(TSPAlgorithms.SHA256, digest, BigInteger.valueOf(42))
    }

    private fun post(body: ByteArray, contentType: String = TimeStampResponder.CONTENT_TYPE_QUERY, chunked: Boolean = false): HttpURLConnection =
        (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            if (chunked) setChunkedStreamingMode(7) else setFixedLengthStreamingMode(body.size)
            outputStream.use { it.write(body) }
        }

    private fun assertGranted(req: TimeStampRequest, conn: HttpURLConnection) {
        assertEquals(200, conn.responseCode)
        assertEquals(TimeStampResponder.CONTENT_TYPE_REPLY, conn.contentType)
        val resp = TimeStampResponse(conn.inputStream.readBytes())
        assertEquals(PKIStatus.GRANTED, resp.status)

        resp.validate(req) // imprint, nonce, policy and certReq match the request
        val token = resp.timeStampToken
        token.validate(JcaSimpleSignerInfoVerifierBuilder().build(cert))
        assertArrayEquals(req.messageImprintDigest, token.timeStampInfo.messageImprintDigest)
        assertEquals(TimeSource.DEVICE_CLOCK.policy, token.timeStampInfo.policy)
        assertEquals(1, token.certificates.getMatches(null).size)
        assertTrue(Math.abs(token.timeStampInfo.genTime.time - System.currentTimeMillis()) < 60_000)
    }

    @Test
    fun grantsTokenOverHttp() {
        val req = request()
        assertGranted(req, post(req.encoded))
    }

    @Test
    fun acceptsChunkedBody() {
        val req = request()
        assertGranted(req, post(req.encoded, chunked = true))
    }

    @Test
    fun acceptsOwnPolicy() {
        val req = request(TimeSource.DEVICE_CLOCK.policy)
        assertGranted(req, post(req.encoded))
    }

    @Test
    fun rejectsForeignPolicy() {
        val conn = post(request(ASN1ObjectIdentifier("1.2.3.4")).encoded)
        val resp = TimeStampResponse(conn.inputStream.readBytes())
        assertEquals(PKIStatus.REJECTION, resp.status)
        assertNotNull(resp.failInfo)
        assertTrue(resp.failInfo.intValue() and PKIFailureInfo.unacceptedPolicy != 0)
    }

    @Test
    fun rejectsMalformedRequestWithTspStatus() {
        val conn = post(byteArrayOf(1, 2, 3))
        assertEquals(200, conn.responseCode)
        val resp = TimeStampResponse(conn.inputStream.readBytes())
        assertEquals(PKIStatus.REJECTION, resp.status)
        assertTrue(resp.failInfo.intValue() and PKIFailureInfo.badDataFormat != 0)
    }

    @Test
    fun rejectsWrongContentType() {
        assertEquals(415, post(request().encoded, contentType = "application/octet-stream").responseCode)
    }

    @Test
    fun rejectsGet() {
        val conn = URL(server.url).openConnection() as HttpURLConnection
        assertEquals(405, conn.responseCode)
    }

    @Test
    fun certificateIsMarkedForTimeStampingOnly() {
        // RFC 3161 §2.3: critical EKU containing only id-kp-timeStamping.
        assertTrue("2.5.29.37" in cert.criticalExtensionOIDs)
        assertEquals(listOf("1.3.6.1.5.5.7.3.8"), cert.extendedKeyUsage)
    }

    @Test
    fun gnssReadingSetsGenTimePolicyAndAccuracy() {
        val gnssTime = java.util.Date(1_800_000_000_000L) // deliberately far from the JVM clock
        val responder = TimeStampResponder(
            keyPair.private, cert, clock = { TimeReading(gnssTime, TimeSource.GNSS) },
        )
        val req = request()
        val resp = TimeStampResponse(responder.respond(req.encoded))
        assertEquals(PKIStatus.GRANTED, resp.status)
        resp.validate(req)
        val info = resp.timeStampToken.timeStampInfo
        assertEquals(gnssTime, info.genTime)
        assertEquals(TimeSource.GNSS.policy, info.policy)
        assertEquals(1, info.genTimeAccuracy.seconds)
    }

    @Test
    fun deviceClockReadingClaimsNoAccuracy() {
        val info = TimeStampResponse(TimeStampResponder(keyPair.private, cert).respond(request().encoded))
            .timeStampToken.timeStampInfo
        assertEquals(TimeSource.DEVICE_CLOCK.policy, info.policy)
        assertEquals(null, info.genTimeAccuracy)
    }

    @Test
    fun rejectsPolicyOfOtherTimeSource() {
        // Device clock in use, but the client insists on the GNSS policy.
        val resp = TimeStampResponse(
            TimeStampResponder(keyPair.private, cert).respond(request(TimeSource.GNSS.policy).encoded),
        )
        assertEquals(PKIStatus.REJECTION, resp.status)
    }

    @Test
    fun gnssExtrapolatesOnMonotonicClock() {
        val fixUtc = 1_800_000_000_000L
        val fixElapsed = 5_000_000_000L // 5 s after boot
        val tenMinutes = 600_000_000_000L
        assertEquals(java.util.Date(fixUtc + 600_000), GnssTime.extrapolate(fixUtc, fixElapsed, fixElapsed + tenMinutes))
    }

    @Test
    fun gnssRejectsFutureAndStaleFixes() {
        val fixUtc = 1_800_000_000_000L
        val fixElapsed = 5_000_000_000L
        // Fix "after" now: elapsed-realtime belongs to another boot.
        assertEquals(null, GnssTime.extrapolate(fixUtc, fixElapsed, fixElapsed - 1))
        val tooOld = (GnssTime.MAX_FIX_AGE_MS + 1) * 1_000_000
        assertEquals(null, GnssTime.extrapolate(fixUtc, fixElapsed, fixElapsed + tooOld))
        assertEquals(null, GnssTime.extrapolate(0, fixElapsed, fixElapsed))
    }

    @Test
    fun requiredPathIsEnforced() {
        val req = request()
        val guarded = LocalTsaServer(TimeStampResponder(keyPair.private, cert), requiredPath = "/s3cret")
        try {
            assertTrue(guarded.url.endsWith(":${guarded.port}/s3cret"))
            val wrong = (URL("http://127.0.0.1:${guarded.port}/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", TimeStampResponder.CONTENT_TYPE_QUERY)
                outputStream.use { it.write(req.encoded) }
            }
            assertEquals(404, wrong.responseCode)
            val right = (URL(guarded.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", TimeStampResponder.CONTENT_TYPE_QUERY)
                outputStream.use { it.write(req.encoded) }
            }
            assertEquals(200, right.responseCode)
        } finally {
            guarded.close()
        }
    }

    @Test
    fun handlerFailureIsBadGateway() {
        val failing = LocalTsaServer({ throw java.io.IOException("upstream down") })
        try {
            val conn = (URL(failing.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", TimeStampResponder.CONTENT_TYPE_QUERY)
                outputStream.use { it.write(request().encoded) }
            }
            assertEquals(502, conn.responseCode)
        } finally {
            failing.close()
        }
    }

    @Test
    fun proxyNeverSendsKeyOverPlainHttp() {
        assertEquals(null, TsaProxy.urlFor("http://tsa.example.com/", "key"))
    }

    @Test
    fun certificateMeetsC2paProfile() {
        // c2pa-rs check_certificate_profile: AKI required even when self-signed,
        // otherwise the manifest is reported as signingCredential.invalid.
        assertNotNull(cert.getExtensionValue("2.5.29.35"))
        assertNotNull(cert.getExtensionValue("2.5.29.14"))
        assertTrue(cert.keyUsage[0]) // digitalSignature
        assertEquals(-1, cert.basicConstraints) // end entity, not a CA
    }
}
