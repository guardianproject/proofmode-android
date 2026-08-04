package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EthJsonRpcClientTest {

    @Test
    fun `getTransactionByHash returns transaction when present`() {
        val client = clientWithResponses(
            """{"jsonrpc":"2.0","id":1,"result":{"hash":"0xabc","blockNumber":null}}"""
        )

        runBlocking {
            val result = client.getTransactionByHash(listOf("https://rpc.test"), "0xabc")
            assertEquals("0xabc", result?.optString("hash"))
        }
    }

    @Test
    fun `getTransactionByHash returns null when transaction missing`() {
        val client = clientWithResponses("""{"jsonrpc":"2.0","id":1,"result":null}""")

        runBlocking {
            val result = client.getTransactionByHash(listOf("https://rpc.test"), "0xabc")
            assertNull(result)
        }
    }

    @Test
    fun `getTransactionReceipt logs poll attempt when provided`() {
        val client = clientWithResponses("""{"jsonrpc":"2.0","id":1,"result":null}""")

        runBlocking {
            val result = client.getTransactionReceipt(
                rpcUrls = listOf("https://rpc.test"),
                txHash = "0xabc",
                pollAttempt = 51,
            )
            assertNull(result)
        }
    }

    @Test
    fun `getTransactionReceipt returns receipt on success`() {
        val client = clientWithResponses(
            """{"jsonrpc":"2.0","id":1,"result":{"status":"0x1","blockNumber":"0x10","logs":[]}}"""
        )

        runBlocking {
            val result = client.getTransactionReceipt(listOf("https://rpc.test"), "0xabc")
            assertEquals("0x1", result?.optString("status"))
        }
    }

    @Test
    fun `getTransactionReceipt returns null on timeout`() {
        val client = clientWithResponses("""{"jsonrpc":"2.0","id":1,"result":null}""")

        runBlocking {
            val result = client.getTransactionReceipt(listOf("https://rpc.test"), "0xabc")
            assertNull(result)
        }
    }

    @Test
    fun `getTransactionReceipt can detect reverted transaction status`() {
        val client = clientWithResponses(
            """{"jsonrpc":"2.0","id":1,"result":{"status":"0x0","blockNumber":"0x10","logs":[]}}"""
        )

        runBlocking {
            val result = client.getTransactionReceipt(listOf("https://rpc.test"), "0xabc")
            assertEquals("0x0", result?.optString("status"))
        }
    }

    @Test
    fun `parseAttestedUid filters by topic0 and eas contract address`() {
        val easAddress = "0xAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAa"
        val uid = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        val logs = JSONArray(
            """[
                {
                  "address": "$easAddress",
                  "topics": [
                    "${EthJsonRpcClient.ATTESTED_EVENT_TOPIC0}",
                    "$uid"
                  ]
                },
                {
                  "address": "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "topics": [
                    "${EthJsonRpcClient.ATTESTED_EVENT_TOPIC0}",
                    "0xdeadbeef"
                  ]
                }
            ]"""
        )

        assertEquals(uid, EthJsonRpcClient().parseAttestedUid(logs, easAddress))
    }

    @Test
    fun `parseAttestedUid reads uid from data when indexed refUID is zero`() {
        val easAddress = "0xC2679fBd37d54388Ce493F1DB75320D236e1815e"
        val uid =
            "0x311c1843e27c3b5d8c0ad2bfd3d3877585b6d08bd5bf08f630799802bc235ded"
        val logs = JSONArray(
            """[
                {
                  "address": "$easAddress",
                  "topics": [
                    "${EthJsonRpcClient.ATTESTED_EVENT_TOPIC0}",
                    "0x0000000000000000000000000000000000000000000000000000000000000000",
                    "0x0000000000000000000000004dbf28dc14c26ec399f8b849b4ca50c8bd89a5ca",
                    "0xde87be8a0b537bf996e1a623a3f98aaa51aad3b3362b56c3fac10fd81582ba81"
                  ],
                  "data": "0x311c1843e27c3b5d8c0ad2bfd3d3877585b6d08bd5bf08f630799802bc235ded"
                }
            ]"""
        )

        assertEquals(uid, EthJsonRpcClient().parseAttestedUid(logs, easAddress))
    }

    @Test
    fun `parseAttestedUid prefers non-zero indexed uid over data`() {
        val easAddress = "0xC2679fBd37d54388Ce493F1DB75320D236e1815e"
        val indexedUid =
            "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        val logs = JSONArray(
            """[
                {
                  "address": "$easAddress",
                  "topics": [
                    "${EthJsonRpcClient.ATTESTED_EVENT_TOPIC0}",
                    "$indexedUid"
                  ],
                  "data": "0x311c1843e27c3b5d8c0ad2bfd3d3877585b6d08bd5bf08f630799802bc235ded"
                }
            ]"""
        )

        assertEquals(indexedUid, EthJsonRpcClient().parseAttestedUid(logs, easAddress))
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
    fun `parseSchemaUidFromGetSchemaResult detects registered schema`() {
        val registered =
            "0x0000000000000000000000000000000000000000000000000000000000000020" +
                "de87be8a0b537bf996e1a623a3f98aaa51aad3b3362b56c3fac10fd81582ba81" +
                "0000000000000000000000000000000000000000000000000000000000000040"
        assertEquals(
            "0xde87be8a0b537bf996e1a623a3f98aaa51aad3b3362b56c3fac10fd81582ba81",
            EthJsonRpcClient().parseSchemaUidFromGetSchemaResult(registered),
        )
    }

    @Test
    fun `parseSchemaUidFromGetSchemaResult returns null for empty schema`() {
        val empty =
            "0x0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000000"
        assertNull(EthJsonRpcClient().parseSchemaUidFromGetSchemaResult(empty))
    }

    @Test
    fun `isSchemaRegistered returns true when getSchema returns uid`() {
        val client = clientWithResponses(
            """{"jsonrpc":"2.0","id":1,"result":"0x0000000000000000000000000000000000000000000000000000000000000020de87be8a0b537bf996e1a623a3f98aaa51aad3b3362b56c3fac10fd81582ba81"}"""
        )

        runBlocking {
            val registered = client.isSchemaRegistered(
                rpcUrls = listOf("https://rpc.test"),
                schemaRegistryAddress = "0x0a7E2Ff54e76B8E6659aedc9103FB21c038050D0",
                schemaId = "0xde87be8a0b537bf996e1a623a3f98aaa51aad3b3362b56c3fac10fd81582ba81",
            )
            assertTrue(registered)
        }
    }

    @Test
    fun `isSchemaRegistered returns false when getSchema returns zero uid`() {
        val client = clientWithResponses(
            """{"jsonrpc":"2.0","id":1,"result":"0x00000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000"}"""
        )

        runBlocking {
            val registered = client.isSchemaRegistered(
                rpcUrls = listOf("https://rpc.test"),
                schemaRegistryAddress = "0xA7b39296258348C78294F95B872b282326A97BDF",
                schemaId = "0xde87be8a0b537bf996e1a623a3f98aaa51aad3b3362b56c3fac10fd81582ba81",
            )
            assertFalse(registered)
        }
    }

    @Test
    fun `estimateGas returns hex quantity on success`() {
        val client = clientWithResponses("""{"jsonrpc":"2.0","id":1,"result":"0xe1cba"}""")

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

    @Test
    fun `getBlockTimestamp reads block timestamp from rpc response`() {
        val client = clientWithResponses(
            """{"jsonrpc":"2.0","id":1,"result":{"timestamp":"0x5f5e100"}}"""
        )

        runBlocking {
            val timestamp = client.getBlockTimestamp(listOf("https://rpc.test"), "0x10")
            assertEquals(0x5f5e100L, timestamp)
        }
    }

    private fun clientWithResponses(body: String): EthJsonRpcClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody())
                        .build()
                }
            )
            .build()
        return EthJsonRpcClient(okHttpClient)
    }
}
