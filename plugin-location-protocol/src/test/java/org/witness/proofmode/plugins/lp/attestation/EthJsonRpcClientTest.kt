package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.attestation.FakeEthRpc.Reply
import org.witness.proofmode.plugins.lp.attestation.EthJsonRpcClient.Companion.EAS_VERSION_LOWER_SELECTOR as VERSION_LOWER
import org.witness.proofmode.plugins.lp.attestation.EthJsonRpcClient.Companion.EAS_VERSION_UPPER_SELECTOR as VERSION_UPPER
import org.witness.proofmode.plugins.lp.attestation.FakeEthRpc.Companion.abiString
import org.witness.proofmode.plugins.lp.attestation.FakeEthRpc.Companion.word

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EthJsonRpcClientTest {

    @Test
    fun `parseAttestedUid returns the data word when topics1 is a non-zero recipient`() {
        val easAddress = "0xC2679fBd37d54388Ce493F1DB75320D236e1815e"
        val uid = "0x311c1843e27c3b5d8c0ad2bfd3d3877585b6d08bd5bf08f630799802bc235ded"
        val logs = JSONArray(
            """[
                {
                  "address": "$easAddress",
                  "topics": [
                    "${EthJsonRpcClient.ATTESTED_EVENT_TOPIC0}",
                    "0x0000000000000000000000001234567890abcdef1234567890abcdef12345678",
                    "0x0000000000000000000000004dbf28dc14c26ec399f8b849b4ca50c8bd89a5ca",
                    "0xde87be8a0b537bf996e1a623a3f98aaa51aad3b3362b56c3fac10fd81582ba81"
                  ],
                  "data": "$uid"
                }
            ]"""
        )

        assertEquals(uid, EthJsonRpcClient().parseAttestedUid(logs, easAddress))
    }

    @Test
    fun `getTransactionReceipt falls back to next rpc url`() {
        var callCount = 0
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    callCount++
                    val body = if (callCount == 1) {
                        "Not Found: No proxy rule for this subdomain"
                    } else {
                        """{"jsonrpc":"2.0","id":1,"result":{"status":"0x1","blockNumber":"0x10","logs":[]}}"""
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (callCount == 1) 404 else 200)
                        .message(if (callCount == 1) "Not Found" else "OK")
                        .body(body.toResponseBody())
                        .build()
                }
            )
            .build()
        val client = EthJsonRpcClient(okHttpClient)

        runBlocking {
            val result = client.getTransactionReceipt(
                listOf("https://broken-rpc.test", "https://working-rpc.test"),
                "0xABC",
            )
            assertEquals("0x1", result?.optString("status"))
            assertEquals(2, callCount)
        }
    }

    @Test
    fun `estimateGas returns hex quantity on success`() {
        val client = EthJsonRpcClient(methodRoutedRpcClient { "\"0xe1cba\"" })

        runBlocking {
            val gas = client.estimateGas(
                rpcUrls = listOf("https://rpc.test"),
                from = "0xbed1e6e87bb90d4c3b84cd78e7b4d92fd949d5ce",
                to = "0xC2679fBD37d54388Ce493F1DB75320D236e1815e",
                data = "0xdeadbeef",
            )
            assertEquals("0xe1cba", gas)
        }
    }

    private val easAddress = "0xC2679fBD37d54388Ce493F1DB75320D236e1815e"
    private val rpcUrl = "https://rpc.test"

    @Test
    fun readEasVersion_versionUpperHit() = runTest {
        val rpc = FakeEthRpc { Reply.Result(abiString("0.26")) }

        val version = EthJsonRpcClient(rpc.okHttpClient).readEasVersion(listOf(rpcUrl), easAddress)

        assertEquals("0.26", version)
        assertEquals(listOf(rpcUrl to easAddress), rpc.calls.map { it.url to it.to })
    }

    @Test
    fun readEasVersion_revertThenLowerHit() = runTest {
        val rpc = FakeEthRpc { call ->
            if (call.data == VERSION_UPPER) Reply.Revert else Reply.Result(abiString("1.4.1-beta.3"))
        }

        val version = EthJsonRpcClient(rpc.okHttpClient).readEasVersion(listOf(rpcUrl), easAddress)

        assertEquals("1.4.1-beta.3", version)
        assertEquals(listOf(VERSION_UPPER, VERSION_LOWER), rpc.calls.map { it.data })
    }

    @Test
    fun `readEasVersion treats every malformed payload as a miss`() = runTest {
        val misses = listOf(
            "empty result" to "0x",
            "empty string" to abiString(""),
            "truncated payload" to "0x" + word(32) + word(64) + "3032",
            "offset past data" to "0x" + word(4096) + word(1) + "30" + "00".repeat(31),
            "shorter than two words" to "0x" + word(32),
            // A leading '-' makes the length word parse negative; the block's slicing throws.
            "negative length word" to "0x" + word(32) + "-" + "0".repeat(62) + "8" + "00".repeat(32),
        )
        for ((label, miss) in misses) {
            val bothMiss = FakeEthRpc { Reply.Result(miss) }
            assertNull(
                label,
                EthJsonRpcClient(bothMiss.okHttpClient).readEasVersion(listOf(rpcUrl), easAddress),
            )
        }

        // A VERSION() reply the decoder rejects moves on to version().
        val onlyLowerAnswers = FakeEthRpc { call ->
            if (call.data == VERSION_UPPER) Reply.Result(abiString("")) else Reply.Result(abiString("0.26"))
        }
        assertEquals(
            "0.26",
            EthJsonRpcClient(onlyLowerAnswers.okHttpClient).readEasVersion(listOf(rpcUrl), easAddress),
        )
    }

    @Test
    fun readEasVersion_firstUrlTransportFailsSecondAnswers() = runTest {
        val rpc = FakeEthRpc { call ->
            if (call.url == "https://one.test") Reply.Http(500) else Reply.Result(abiString("0.26"))
        }

        val version = EthJsonRpcClient(rpc.okHttpClient)
            .readEasVersion(listOf("https://one.test", "https://two.test"), easAddress)

        assertEquals("0.26", version)
        assertEquals(
            listOf("https://one.test" to VERSION_UPPER, "https://two.test" to VERSION_UPPER),
            rpc.calls.map { it.url to it.data },
        )
    }

    @Test
    fun readEasVersion_allFailReturnsNull() = runTest {
        val urls = listOf("https://one.test", "https://two.test", "https://three.test")

        val dead = FakeEthRpc { Reply.Http(500) }
        assertNull(EthJsonRpcClient(dead.okHttpClient).readEasVersion(urls, easAddress))

        val reverting = FakeEthRpc { Reply.Revert }
        assertNull(EthJsonRpcClient(reverting.okHttpClient).readEasVersion(urls, easAddress))
    }
}
