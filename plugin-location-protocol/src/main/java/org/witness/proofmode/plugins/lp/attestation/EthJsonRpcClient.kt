package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC helpers for L1 receipt and block polling via chain RPC URLs.
 */
internal class EthJsonRpcClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getTransactionReceipt(rpcUrls: List<String>, txHash: String): JSONObject? =
        withContext(Dispatchers.IO) {
            for (rpcUrl in rpcUrls) {
                val receipt = fetchResultObject(rpcUrl, "eth_getTransactionReceipt", txHash)
                if (receipt != null) return@withContext receipt
            }
            null
        }

    suspend fun getTransactionByHash(rpcUrls: List<String>, txHash: String): JSONObject? =
        withContext(Dispatchers.IO) {
            for (rpcUrl in rpcUrls) {
                val transaction = fetchResultObject(rpcUrl, "eth_getTransactionByHash", txHash)
                if (transaction != null) return@withContext transaction
            }
            null
        }

    suspend fun getBlockTimestamp(rpcUrls: List<String>, blockNumberHex: String): Long? =
        withContext(Dispatchers.IO) {
            for (rpcUrl in rpcUrls) {
                val timestamp = fetchBlockTimestamp(rpcUrl, blockNumberHex)
                if (timestamp != null) return@withContext timestamp
            }
            null
        }

    /**
     * A copy whose every call is cut off after [timeout]. Reads that block signing use it so
     * a slow node costs seconds, not the 30 s the other calls tolerate.
     */
    internal fun withCallTimeout(timeout: Long, unit: TimeUnit): EthJsonRpcClient =
        EthJsonRpcClient(okHttpClient.newBuilder().callTimeout(timeout, unit).build())

    /**
     * The EAS contract's version string, or null when no call on any URL produced one.
     *
     * Tries `VERSION()` then `version()` (the 0.26 deployments only have the former, later
     * ones the latter). A reverted or undecodable answer moves to the next selector on the
     * same URL; a dead host skips straight to the next URL since it will not answer the
     * second selector either. URLs are tried in the order given, never sticky-reordered,
     * so every signer asks the same node first.
     */
    suspend fun readEasVersion(rpcUrls: List<String>, easAddress: String): String? =
        withContext(Dispatchers.IO) {
            for (rpcUrl in rpcUrls) {
                for (selector in EAS_VERSION_SELECTORS) {
                    when (val outcome = ethCall(rpcUrl, easAddress, selector)) {
                        is EthCallOutcome.Hit -> decodeAbiString(outcome.resultHex)?.let { return@withContext it }
                        EthCallOutcome.CallFailed -> Unit
                        EthCallOutcome.HostFailed -> break
                    }
                }
            }
            null
        }

    suspend fun estimateGas(
        rpcUrls: List<String>,
        from: String,
        to: String,
        data: String,
        valueHex: String = "0x0",
    ): String? = withContext(Dispatchers.IO) {
        val txObject = JSONObject()
            .put("from", from)
            .put("to", to)
            .put("data", data)
            .put("value", valueHex)
        val payload =
            """{"jsonrpc":"2.0","method":"eth_estimateGas","params":[$txObject],"id":1}"""
        for (rpcUrl in rpcUrls) {
            val response = postJsonRpc(rpcUrl, payload) ?: continue
            val result = response.optString("result")
            if (result.isNotBlank()) {
                return@withContext result
            }
        }
        null
    }

    fun parseAttestedUid(logs: JSONArray, easAddress: String): String? {
        val normalizedEas = easAddress.lowercase()
        for (i in 0 until logs.length()) {
            val log = logs.getJSONObject(i)
            val logAddress = log.optString("address").lowercase()
            if (logAddress != normalizedEas) continue

            val topics = log.optJSONArray("topics") ?: continue
            val topic0 = topics.optString(0).lowercase()
            if (topic0 != ATTESTED_EVENT_TOPIC0) continue

            val dataHex = log.optString("data").removePrefix("0x").lowercase()

            // Attested(address indexed recipient, address indexed attester, bytes32 uid,
            // bytes32 indexed schemaUID): the uid is the only non-indexed word, so it is
            // data[0:32]. topics[1] is the recipient, never the uid.
            return "0x" + dataHex.substring(0, 64)
        }
        return null
    }

    private fun ethCall(rpcUrl: String, to: String, data: String): EthCallOutcome {
        val callObject = JSONObject()
            .put("to", to)
            .put("data", data)
        val payload =
            """{"jsonrpc":"2.0","method":"eth_call","params":[$callObject,"latest"],"id":1}"""
        val response = postJsonRpc(rpcUrl, payload) ?: return EthCallOutcome.HostFailed
        if (response.has("error")) return EthCallOutcome.CallFailed
        val result = response.optString("result")
        if (result.isBlank() || result == "0x") return EthCallOutcome.CallFailed
        return EthCallOutcome.Hit(result)
    }

    /** Decodes an ABI `string` return value; null when malformed, truncated or blank. */
    private fun decodeAbiString(resultHex: String): String? = runCatching {
        val hex = resultHex.removePrefix("0x")
        val lengthStart = hex.substring(0, ABI_WORD_HEX_LENGTH).toInt(16) * 2
        val length = hex.substring(lengthStart, lengthStart + ABI_WORD_HEX_LENGTH).toInt(16)
        val textStart = lengthStart + ABI_WORD_HEX_LENGTH
        val bytes = ByteArray(length) { hex.substring(textStart + it * 2, textStart + it * 2 + 2).toInt(16).toByte() }
        String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Separates a node that answered with no usable value from a node that did not answer. */
    private sealed class EthCallOutcome {
        class Hit(val resultHex: String) : EthCallOutcome()
        object CallFailed : EthCallOutcome()
        object HostFailed : EthCallOutcome()
    }

    private fun fetchResultObject(rpcUrl: String, method: String, txHash: String): JSONObject? {
        val payload = """{"jsonrpc":"2.0","method":"$method","params":["$txHash"],"id":1}"""
        val response = postJsonRpc(rpcUrl, payload) ?: return null
        if (response.isNull("result")) return null
        return response.optJSONObject("result")
    }

    private fun fetchBlockTimestamp(rpcUrl: String, blockNumberHex: String): Long? {
        val payload =
            """{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["$blockNumberHex", false],"id":1}"""
        val result = postJsonRpc(rpcUrl, payload)?.optJSONObject("result") ?: return null
        val timestampHex = result.optString("timestamp").removePrefix("0x")
        if (timestampHex.isEmpty()) return null
        return timestampHex.toLong(16)
    }

    private fun postJsonRpc(rpcUrl: String, payload: String): JSONObject? {
        val request = Request.Builder()
            .url(rpcUrl)
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                JSONObject(bodyStr)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "postJsonRpc failed url=%s", rpcUrl)
            null
        }
    }

    companion object {
        private const val TAG = "EthJsonRpc"

        /** EAS `VERSION()` then `version()` selectors, in the order they are tried. */
        internal const val EAS_VERSION_UPPER_SELECTOR = "0xffa1ad74"
        internal const val EAS_VERSION_LOWER_SELECTOR = "0x54fd4d50"
        private val EAS_VERSION_SELECTORS =
            listOf(EAS_VERSION_UPPER_SELECTOR, EAS_VERSION_LOWER_SELECTOR)

        private const val ABI_WORD_HEX_LENGTH = 64

        const val ATTESTED_EVENT_TOPIC0 =
            "0x8bf46bf4cfd674fa735a3d63ec1c9ad4153f033c290341f3a588b75685141b35"
    }
}
