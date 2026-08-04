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
import java.net.URI
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
    private var lastGoodRpcUrl: String? = null

    suspend fun getTransactionReceipt(
        rpcUrls: List<String>,
        txHash: String,
        pollAttempt: Int? = null,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val normalizedHash = normalizeTxHash(txHash)
        val orderedRpcUrls = orderRpcUrls(rpcUrls)
        for ((index, rpcUrl) in orderedRpcUrls.withIndex()) {
            val receipt = fetchTransactionReceipt(rpcUrl, normalizedHash)
            if (receipt != null) {
                lastGoodRpcUrl = rpcUrl
                Timber.tag(TAG).d(
                    "getTransactionReceipt: success host=%s rpcIndex=%d pollAttempt=%s txHash=%s",
                    rpcHost(rpcUrl),
                    index + 1,
                    pollAttempt?.toString() ?: "n/a",
                    normalizedHash,
                )
                return@withContext receipt
            }
            Timber.tag(TAG).d(
                "getTransactionReceipt: no receipt host=%s rpcIndex=%d pollAttempt=%s txHash=%s",
                rpcHost(rpcUrl),
                index + 1,
                pollAttempt?.toString() ?: "n/a",
                normalizedHash,
            )
        }
        null
    }

    suspend fun getTransactionByHash(rpcUrls: List<String>, txHash: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val normalizedHash = normalizeTxHash(txHash)
            for (rpcUrl in rpcUrls) {
                val transaction = fetchTransactionByHash(rpcUrl, normalizedHash)
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

    suspend fun isSchemaRegistered(
        rpcUrls: List<String>,
        schemaRegistryAddress: String,
        schemaId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedSchemaId = normalizeBytes32(schemaId)
        val callData = GET_SCHEMA_SELECTOR + normalizedSchemaId.removePrefix("0x")
        for (rpcUrl in rpcUrls) {
            val resultHex = ethCall(rpcUrl, schemaRegistryAddress, callData) ?: continue
            if (parseSchemaUidFromGetSchemaResult(resultHex) != null) {
                return@withContext true
            }
        }
        false
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
            if (topics.length() < 2) continue
            val topic0 = topics.optString(0).lowercase()
            if (topic0 != ATTESTED_EVENT_TOPIC0) continue

            val topic1 = topics.optString(1)
            val dataHex = log.optString("data").removePrefix("0x").lowercase()

            // EAS Attested(bytes32,bytes32,address,address,bytes32): topics[1] is indexed
            // refUID (zero for new attestations). The attestation UID is the sole non-indexed
            // bytes32 in `data` for sponsored UserOp attestations (see Sepolia tx
            // 0x8606cd03f0ff84dd049a66da0bd806d54cdfdd40d77ebdfe55778594e37a089d).
            if (!isZeroBytes32(topic1)) {
                return topic1
            }
            if (dataHex.length >= 64) {
                val dataUid = "0x${dataHex.substring(0, 64)}"
                if (!isZeroBytes32(dataUid)) {
                    return dataUid
                }
            }
            return topic1.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun isZeroBytes32(hex: String): Boolean {
        val stripped = hex.removePrefix("0x").lowercase()
        return stripped.isEmpty() || stripped.all { it == '0' }
    }

    private fun ethCall(rpcUrl: String, to: String, data: String): String? {
        val callObject = JSONObject()
            .put("to", to)
            .put("data", data)
        val payload =
            """{"jsonrpc":"2.0","method":"eth_call","params":[$callObject,"latest"],"id":1}"""
        val response = postJsonRpc(rpcUrl, payload) ?: return null
        if (response.has("error")) return null
        val result = response.optString("result")
        return result.takeIf { it.isNotBlank() && it != "0x" }
    }

    internal fun parseSchemaUidFromGetSchemaResult(resultHex: String): String? {
        val hex = resultHex.removePrefix("0x")
        if (hex.length < 128) return null
        val uidWord = hex.substring(64, 128)
        if (uidWord.all { it == '0' }) return null
        return "0x$uidWord"
    }

    private fun fetchTransactionReceipt(rpcUrl: String, txHash: String): JSONObject? {
        val payload =
            """{"jsonrpc":"2.0","method":"eth_getTransactionReceipt","params":["$txHash"],"id":1}"""
        val response = postJsonRpc(rpcUrl, payload) ?: return null
        if (response.isNull("result")) return null
        return response.optJSONObject("result")
    }

    private fun fetchTransactionByHash(rpcUrl: String, txHash: String): JSONObject? {
        val payload =
            """{"jsonrpc":"2.0","method":"eth_getTransactionByHash","params":["$txHash"],"id":1}"""
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
        val method = runCatching {
            JSONObject(payload).optString("method").ifBlank { "unknown" }
        }.getOrDefault("unknown")
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
            Timber.tag(TAG).w(e, "postJsonRpc failed url=%s method=%s", rpcUrl, method)
            null
        }
    }

    private fun orderRpcUrls(rpcUrls: List<String>): List<String> {
        val sticky = lastGoodRpcUrl ?: return rpcUrls
        if (!rpcUrls.contains(sticky)) return rpcUrls
        return listOf(sticky) + rpcUrls.filterNot { it == sticky }
    }

    private fun normalizeTxHash(txHash: String): String {
        val trimmed = txHash.trim()
        val withPrefix = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
        return withPrefix.lowercase()
    }

    private fun normalizeBytes32(value: String): String {
        val trimmed = value.trim().removePrefix("0x").lowercase()
        require(trimmed.length == 64) { "Expected bytes32 hex value" }
        return "0x$trimmed"
    }

    companion object {
        private const val TAG = "EthJsonRpc"

        /** EAS SchemaRegistry.getSchema(bytes32) selector from location_protocol EASAbis. */
        internal const val GET_SCHEMA_SELECTOR = "0xa2ea7c6e"

        const val ATTESTED_EVENT_TOPIC0 =
            "0x8bf46bf4cfd674fa735a3d63ec1c9ad4153f033c290341f3a588b75685141b35"
    }

    private fun rpcHost(rpcUrl: String): String = runCatching {
        URI(rpcUrl).host ?: "unknown"
    }.getOrDefault("unknown")
}
