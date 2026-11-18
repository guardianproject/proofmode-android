package org.witness.proofmode.c2pa.tsa

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.c2pa.proofsign.DeviceRequestSigner
import org.witness.proofmode.c2pa.proofsign.deviceRequestSigner
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ProofSign timestamp relay, against loopback HTTP stubs standing in for ProofSign: the
 * device headers and their signature over the exact bytes sent, untouched replies, refusal to
 * sign anything but a TimeStampReq, no redirects, and the 401 / 5xx / other-error handling.
 * Uses a software P-256 key; the Android Keystore half lives in DeviceKey and needs a device.
 */
class TsaForwarderTest {

    private class Reply(
        val code: Int,
        val contentType: String? = TimeStampResponder.CONTENT_TYPE_REPLY,
        val body: ByteArray = byteArrayOf(),
        val headers: Map<String, String> = emptyMap(),
        /** Announce more body than is sent, then hang up: a connection reset mid-reply. */
        val truncate: Boolean = false,
    )

    private class Recorded(val method: String, val headers: Headers, val body: ByteArray)

    /** A loopback HTTP stub replaying [replies] in order (the last one repeats). */
    private class Stub(private val replies: List<Reply>) {
        val requests = CopyOnWriteArrayList<Recorded>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes()
                requests += Recorded(exchange.requestMethod, exchange.requestHeaders, body)
                val reply = replies[minOf(requests.size - 1, replies.size - 1)]
                reply.contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
                reply.headers.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
                if (reply.truncate) {
                    exchange.sendResponseHeaders(reply.code, reply.body.size.toLong() + 100)
                    exchange.responseBody.write(reply.body)
                    exchange.responseBody.flush()
                    exchange.close()
                    return@createContext
                }
                exchange.sendResponseHeaders(reply.code, if (reply.body.isEmpty()) -1 else reply.body.size.toLong())
                if (reply.body.isNotEmpty()) exchange.responseBody.use { it.write(reply.body) }
                exchange.close()
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}/api/v1/timestamp"
        fun stop() = server.stop(0)
    }

    private val stubs = mutableListOf<Stub>()
    private fun stub(vararg replies: Reply) = Stub(replies.toList()).also { stubs += it }

    @After
    fun tearDown() {
        stubs.forEach { it.stop() }
        TsaProxy.stop()
    }

    private val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private var signCalls = 0
    private var clock: () -> Long = { 1_700_000_000_000L }

    private val signer: DeviceRequestSigner by lazy {
        val inner = deviceRequestSigner("dev-1", { clock() }) { data ->
            Base64.getEncoder().encodeToString(
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private); update(data); sign()
                },
            )
        }
        DeviceRequestSigner { body -> signCalls++; inner.headersFor(body) }
    }

    private val token = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)

    private fun query(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest("c2pa claim signature".toByteArray())
        return TimeStampRequestGenerator().apply { setCertReq(true) }
            .generate(TSPAlgorithms.SHA256, digest, BigInteger.valueOf(42)).encoded
    }

    private fun forwarder(stub: Stub) = TsaForwarder(stub.url.toHttpUrl(), signer, retryDelayMs = 0)

    private fun binding(body: ByteArray) =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))

    private fun verifies(signature: String, input: String) =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public)
            update(input.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signature))
        }

    @Test
    fun forwardsSignedQueryAndPassesReplyThrough() {
        val stub = stub(Reply(200, body = token))
        val q = query()

        val reply = forwarder(stub).handle(q)

        assertArrayEquals(token, reply)
        val sent = stub.requests.single()
        assertEquals("POST", sent.method)
        assertEquals(TimeStampResponder.CONTENT_TYPE_QUERY, sent.headers.getFirst("Content-Type")?.substringBefore(';'))
        assertEquals("dev-1", sent.headers.getFirst("X-Device-ID"))
        assertEquals("1700000000000", sent.headers.getFirst("X-Device-Timestamp"))
        assertArrayEquals(q, sent.body)
        val signature = sent.headers.getFirst("X-Device-Signature")
        assertNotNull(signature)
        assertTrue(verifies(signature!!, "dev-1|1700000000000|${binding(sent.body)}"))
        assertNull(sent.headers.getFirst("X-API-Key"))
    }

    @Test
    fun refusesNonTimestampBodyWithoutSigning() {
        val stub = stub(Reply(200, body = token))
        val oversized = query() + ByteArray(TsaForwarder.MAX_QUERY_BYTES)

        for (body in listOf("not der".toByteArray(), oversized)) {
            assertThrows(IOException::class.java) { forwarder(stub).handle(body) }
        }
        assertEquals(0, signCalls)
        assertEquals(0, stub.requests.size)
    }

    @Test
    fun refusesValidRequestWithTrailingBytes() {
        val stub = stub(Reply(200, body = token))

        assertThrows(IOException::class.java) {
            forwarder(stub).handle(query() + "arbitrary payload".toByteArray())
        }
        assertEquals(0, signCalls)
        assertEquals(0, stub.requests.size)
    }

    @Test
    fun signerFailureIsAnIoErrorAndNotRetried() {
        val stub = stub(Reply(200, body = token))
        val failing = DeviceRequestSigner { signCalls++; throw IllegalStateException("device key missing") }

        val e = assertThrows(IOException::class.java) {
            TsaForwarder(stub.url.toHttpUrl(), failing, retryDelayMs = 0).handle(query())
        }
        assertFalse("a signing failure is not transient: $e", e is TsaUnavailableException)
        assertEquals(1, signCalls)
        assertEquals(0, stub.requests.size)
    }

    @Test
    fun connectionResetMidReplyIsRetried() {
        val stub = stub(Reply(200, body = token, truncate = true), Reply(200, body = token))

        assertArrayEquals(token, forwarder(stub).handle(query()))
        assertEquals(2, stub.requests.size)
    }

    @Test
    fun interruptedRetryDelayIsAnIoError() {
        val stub = stub(Reply(502, contentType = null))
        var thrown: Throwable? = null
        val worker = Thread {
            try {
                TsaForwarder(stub.url.toHttpUrl(), signer, retryDelayMs = 30_000).handle(query())
            } catch (t: Throwable) {
                thrown = t
            }
        }

        worker.start()
        while (stub.requests.isEmpty()) Thread.sleep(10)
        Thread.sleep(100)
        worker.interrupt()
        worker.join(5_000)

        assertFalse("handle() did not return after interrupt", worker.isAlive)
        assertTrue("expected InterruptedIOException, got $thrown", thrown is java.io.InterruptedIOException)
    }

    @Test
    fun doesNotFollowRedirect() {
        val target = stub(Reply(200, body = token))
        val redirecting = stub(Reply(302, contentType = null, headers = mapOf("Location" to target.url)))

        val e = assertThrows(IOException::class.java) { forwarder(redirecting).handle(query()) }
        assertFalse("a redirect is not a transient failure: $e", e is TsaUnavailableException)
        assertEquals(1, redirecting.requests.size)
        assertEquals(0, target.requests.size)
    }

    @Test
    fun unauthorizedIsDistinctNotRetriedAndLogsSkew() {
        // Ten minutes behind the server: the Date header the stub sends is "now".
        clock = { System.currentTimeMillis() - 600_000 }
        val stub = stub(Reply(401, contentType = "application/json", body = "{}".toByteArray()))

        val e = assertThrows(TsaAuthException::class.java) { forwarder(stub).handle(query()) }

        assertEquals(1, stub.requests.size)
        val message = e.message!!
        assertTrue(message, message.contains("dev-1"))
        val skew = Regex("skew=(-?\\d+)ms").find(message)?.groupValues?.get(1)?.toLong()
        assertNotNull(message, skew)
        assertTrue(message, skew!! in 590_000..610_000)
        val signature = stub.requests.single().headers.getFirst("X-Device-Signature")!!
        assertFalse(message, message.contains(signature))
    }

    @Test
    fun badGatewayRetriedOnceWithFreshSignature() {
        val stub = stub(Reply(502, contentType = null), Reply(200, body = token))

        assertArrayEquals(token, forwarder(stub).handle(query()))
        assertEquals(2, stub.requests.size)
        assertEquals(2, signCalls)
    }

    @Test
    fun persistentBadGatewayIsUnavailable() {
        val stub = stub(Reply(502, contentType = null))

        assertThrows(TsaUnavailableException::class.java) { forwarder(stub).handle(query()) }
        assertEquals(2, stub.requests.size)
    }

    @Test
    fun otherClientErrorsNotRetried() {
        val stub = stub(Reply(413, contentType = null))

        val e = assertThrows(IOException::class.java) { forwarder(stub).handle(query()) }
        assertFalse("4xx is not transient: $e", e is TsaUnavailableException)
        assertEquals(1, stub.requests.size)
    }

    @Test
    fun nonTimestampReplyIsRejected() {
        val stub = stub(Reply(200, contentType = "text/html", body = "<html>login</html>".toByteArray()))

        assertThrows(IOException::class.java) { forwarder(stub).handle(query()) }
    }

    @Test
    fun plainHttpOnlyForLoopback() {
        assertThrows(IllegalArgumentException::class.java) {
            TsaForwarder("http://tsa.example.com/".toHttpUrl(), signer)
        }
        assertNull(TsaProxy.urlFor("http://tsa.example.com/", signer))
        TsaForwarder(stub(Reply(200, body = token)).url.toHttpUrl(), signer)
    }

    @Test
    fun proxyRestartsWhenUpstreamChanges() {
        val a = stub(Reply(200, body = token))
        val b = stub(Reply(200, body = token))

        val first = TsaProxy.urlFor(a.url, signer)
        val second = TsaProxy.urlFor(b.url, signer)

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first, second)
        val conn = (URL(second).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", TimeStampResponder.CONTENT_TYPE_QUERY)
            outputStream.use { it.write(query()) }
        }
        assertEquals(200, conn.responseCode)
        assertEquals(0, a.requests.size)
        assertEquals(1, b.requests.size)
    }
}
