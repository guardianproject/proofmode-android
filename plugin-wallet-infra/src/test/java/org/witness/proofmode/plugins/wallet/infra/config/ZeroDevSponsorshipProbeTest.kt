package org.witness.proofmode.plugins.wallet.infra.config

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ZeroDevSponsorshipProbeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Sends every call to [server], keeping the production path so the URL can be asserted. */
    private fun probe(prefilterTimeoutMs: Long = 5_000): ZeroDevSponsorshipProbe {
        val base = server.url("/")
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val rewritten = original.url.newBuilder()
                    .scheme(base.scheme)
                    .host(base.host)
                    .port(base.port)
                    .build()
                chain.proceed(original.newBuilder().url(rewritten).build())
            }
            .build()
        return ZeroDevSponsorshipProbe(
            client = client,
            prefilterTimeoutMs = prefilterTimeoutMs,
        )
    }

    /**
     * Answers the pre-filter GET and each chain's POST separately. The pre-filter is a 404 unless
     * given, which the probe reads as unavailable, so tests that do not care about it see the
     * whole catalog probed.
     */
    private fun serve(
        prefilter: () -> MockResponse = { MockResponse().setResponseCode(404) },
        post: (decimalChain: String) -> MockResponse = { MockResponse().setBody(SPONSORED_BODY) },
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET") {
                    prefilter()
                } else {
                    post(request.path.orEmpty().substringAfterLast("/chain/"))
                }
        }
    }

    private fun byPath(responses: Map<String, MockResponse>) =
        serve(post = { decimal -> responses[decimal] ?: MockResponse().setResponseCode(404) })

    private fun prefilterBody(vararg chainIds: Long): () -> MockResponse = {
        val chains = chainIds.joinToString(",") { """{"chain_id":$it,"name":"c$it","testnet":false}""" }
        MockResponse().setBody("""{"name":"p","chains":[$chains]}""")
    }

    private fun body(json: String): () -> MockResponse = { MockResponse().setBody(json) }

    /** Everything the server saw, minus the requests not yet made when the probe returned. */
    private fun recorded(): List<RecordedRequest> =
        (1..server.requestCount).map { server.takeRequest(5, TimeUnit.SECONDS)!! }

    private fun posts() = recorded().filter { it.method == "POST" }

    private fun postedChains() = posts().map { it.path.orEmpty().substringAfterLast("/chain/") }

    @Test
    fun requestBody_matchesRecordedShape() = runTest {
        serve()

        probe().probe(PROJECT_ID, listOf(SEPOLIA))

        val request = posts().single()
        assertEquals("/api/v3/$PROJECT_ID/chain/11155111", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertNull(request.getHeader("X-API-KEY"))
        assertNull(request.getHeader("Authorization"))
        val sent = JSONObject(request.body.readUtf8())
        val word1 = "0x" + "0".repeat(63) + "1"
        val expected = JSONObject(
            """
            {"jsonrpc":"2.0","id":1,"method":"pm_getPaymasterStubData","params":[
              {"sender":"0x1111111111111111111111111111111111111111","nonce":"0x0","callData":"0x",
               "callGasLimit":"0x0","verificationGasLimit":"0x0","preVerificationGas":"0x0",
               "maxFeePerGas":"0x0","maxPriorityFeePerGas":"0x0",
               "signature":"0x${"0".repeat(63)}1${"0".repeat(63)}11b",
               "eip7702Auth":{"chainId":"0xaa36a7","address":"0xd6CEDDe84be40893d153Be9d467CD6aD37875b28",
                 "nonce":"0x0","yParity":"0x0","r":"$word1","s":"$word1"}},
              "0x0000000071727De22E5E9d8BAf0edAc6f37da032","0xaa36a7",{}]}
            """.trimIndent(),
        )
        assertEquals(toPlain(expected), toPlain(sent))
    }

    @Test
    fun eachChainClassifiedFromItsOwnResponse() = runTest {
        byPath(
            mapOf(
                "11155111" to MockResponse().setBody(SPONSORED_BODY),
                "421614" to MockResponse().setBody(GAS_ONLY_BODY),
                "8453" to MockResponse().setResponseCode(400)
                    .setBody("""{"error":"No sponsoring policies found"}"""),
                "1" to MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32500,"message":"AA23 reverted"}}""",
                ),
                "42161" to MockResponse().setResponseCode(502)
                    .setBody("<html><body>Bad Gateway</body></html>"),
                "10" to MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            ),
        )

        val result = probe().probe(PROJECT_ID, listOf(MAINNET, ARB_SEPOLIA, BASE, SEPOLIA, ARB_ONE, OPTIMISM))

        assertEquals(listOf(SEPOLIA), result)
    }

    @Test
    fun callsRunInParallel() = runTest {
        val chains = listOf(MAINNET, ARB_SEPOLIA, BASE, SEPOLIA, ARB_ONE)
        val allArrived = CountDownLatch(chains.size)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setResponseCode(404)
                allArrived.countDown()
                // A sequential probe never gets past the first request here.
                return if (allArrived.await(3, TimeUnit.SECONDS)) {
                    MockResponse().setBody(SPONSORED_BODY)
                } else {
                    MockResponse().setResponseCode(500)
                }
            }
        }

        val result = probe().probe(PROJECT_ID, chains)

        assertEquals(chains, result)
    }

    @Test
    fun prefilterRequest_isKeylessGetOnProjectPath_beforeAnyPost() = runTest {
        serve(prefilter = prefilterBody(11155111))

        probe().probe(PROJECT_ID, listOf(SEPOLIA))

        val requests = recorded()
        val first = requests.first()
        assertEquals("GET", first.method)
        assertEquals("/v2/projects/$PROJECT_ID", first.path)
        assertEquals("application/json", first.getHeader("accept"))
        assertNull(first.getHeader("X-API-KEY"))
        assertNull(first.getHeader("Authorization"))
        assertEquals(1, requests.count { it.method == "GET" })
    }

    @Test
    fun enabledIntersectGiven_probesOnlyIntersection_inCallerOrder() = runTest {
        serve(prefilter = prefilterBody(11155111, 1, 137))

        val result = probe().probe(PROJECT_ID, listOf(MAINNET, ARB_SEPOLIA, SEPOLIA))

        assertEquals(setOf("1", "11155111"), postedChains().toSet())
        assertEquals(listOf(MAINNET, SEPOLIA), result)
    }

    @Test
    fun prefilterBadResponse_probesWholeCatalog() = runTest {
        val badResponses = listOf(
            "http 500" to { MockResponse().setResponseCode(500).setBody("<html>boom</html>") },
            "disconnect" to { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) },
            "not json" to { MockResponse().setBody("not json") },
            "empty body" to { MockResponse().setBody("") },
            "chains not an array" to { MockResponse().setBody("""{"chains":{}}""") },
            "string chain_id" to { MockResponse().setBody("""{"chains":[{"chain_id":"1"}]}""") },
            "fractional chain_id" to { MockResponse().setBody("""{"chains":[{"chain_id":1.5}]}""") },
            "null element" to { MockResponse().setBody("""{"chains":[null]}""") },
            // One good element does not rescue a bad sibling: only a fully read list narrows.
            "good then bad sibling" to {
                MockResponse().setBody("""{"chains":[{"chain_id":1},{"chain_id":"137"}]}""")
            },
        )
        val given = listOf(MAINNET, SEPOLIA, BASE)
        badResponses.forEach { (label, response) ->
            val postCount = AtomicInteger()
            serve(prefilter = response, post = { postCount.incrementAndGet(); MockResponse().setBody(SPONSORED_BODY) })

            val result = probe().probe(PROJECT_ID, given)

            assertEquals(label, given, result)
            assertEquals(label, given.size, postCount.get())
        }
    }

    @Test
    fun prefilterTimeout_probesWholeCatalog_withinBound() = runTest {
        serve(prefilter = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })

        val started = System.nanoTime()
        val result = probe(prefilterTimeoutMs = 300).probe(PROJECT_ID, listOf(MAINNET, SEPOLIA))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(setOf("1", "11155111"), postedChains().toSet())
        assertEquals(listOf(MAINNET, SEPOLIA), result)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun emptyChainsArray_isKnownEmpty_andSendsNoProbe() = runTest {
        serve(prefilter = body("""{"chains":[]}"""))

        val result = probe().probe(PROJECT_ID, listOf(MAINNET, SEPOLIA))

        assertEquals(emptyList<String>(), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cleanPrefilter_butEveryProbeUnknown_returnsNull() = runTest {
        serve(
            prefilter = prefilterBody(11155111, 1),
            post = { MockResponse().setResponseCode(500).setBody("<html>boom</html>") },
        )

        val result = probe().probe(PROJECT_ID, listOf(MAINNET, ARB_SEPOLIA, SEPOLIA))

        assertNull(result)
    }

    private fun toPlain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { toPlain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { toPlain(value.get(it)) }
        else -> value
    }

    private companion object {
        const val PROJECT_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val MAINNET = "eip155:1"
        const val ARB_ONE = "eip155:42161"
        const val BASE = "eip155:8453"
        const val SEPOLIA = WalletChainPolicy.DEFAULT_CHAIN_ID
        const val ARB_SEPOLIA = "eip155:421614"
        const val OPTIMISM = "eip155:10"
        const val SPONSORED_BODY =
            """{"jsonrpc":"2.0","id":1,"result":{"paymaster":"0x777777777777AeC03fd955926DbF81597e66834C","paymasterData":"0x01"}}"""
        const val GAS_ONLY_BODY =
            """{"jsonrpc":"2.0","id":1,"result":{"callGasLimit":"0x0","preVerificationGas":"0xc350"}}"""
    }
}
