package org.witness.proofmode.plugins.lp.attestation

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList

/** A fake JSON-RPC node behind an [OkHttpClient] interceptor that records every `eth_call`. */
internal class FakeEthRpc(private val reply: (Call) -> Reply) {

    data class Call(val url: String, val to: String, val data: String)

    sealed class Reply {
        data class Result(val hex: String) : Reply()
        object Revert : Reply()
        data class Http(val code: Int) : Reply()
    }

    val calls = CopyOnWriteArrayList<Call>()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                val callObject = JSONObject(body).getJSONArray("params").getJSONObject(0)
                val call = Call(
                    url = request.url.toString().removeSuffix("/"),
                    to = callObject.getString("to"),
                    data = callObject.getString("data"),
                )
                calls += call
                val (code, text) = when (val answer = reply(call)) {
                    is Reply.Result -> 200 to """{"jsonrpc":"2.0","id":1,"result":"${answer.hex}"}"""
                    Reply.Revert ->
                        200 to """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted"}}"""
                    is Reply.Http -> answer.code to ""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body(text.toResponseBody())
                    .build()
            }
        )
        .build()

    companion object {
        /** ABI encoding of a single `string` return value. */
        fun abiString(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val paddedLength = (bytes.size + 31) / 32 * 32
            val data = bytes.joinToString("") { "%02x".format(it) }.padEnd(paddedLength * 2, '0')
            return "0x" + word(32) + word(bytes.size.toLong()) + data
        }

        fun word(value: Long): String = BigInteger.valueOf(value).toString(16).padStart(64, '0')
    }
}

/** An EAS contract address the fake nodes and receipts agree on. */
internal const val TEST_EAS_ADDRESS = "0x4200000000000000000000000000000000000021"

/**
 * An [OkHttpClient] whose node answers each JSON-RPC request with `result` = [result] of its
 * method name, written raw into the envelope (`"null"` for a JSON null).
 */
internal fun methodRoutedRpcClient(result: (method: String) -> String): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                val method = JSONObject(body).getString("method")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"jsonrpc":"2.0","id":1,"result":${result(method)}}""".toResponseBody())
                    .build()
            }
        )
        .build()

/** A mined receipt; [attestedUidWord] null leaves out the `Attested` log. */
internal fun receiptJson(status: String, easAddress: String, attestedUidWord: String?): String {
    val logs = if (attestedUidWord == null) {
        "[]"
    } else {
        """[{"address":"$easAddress","topics":["${EthJsonRpcClient.ATTESTED_EVENT_TOPIC0}","0x${"0".repeat(64)}"],"data":"0x$attestedUidWord"}]"""
    }
    return """{"status":"$status","blockNumber":"0x10","logs":$logs}"""
}
