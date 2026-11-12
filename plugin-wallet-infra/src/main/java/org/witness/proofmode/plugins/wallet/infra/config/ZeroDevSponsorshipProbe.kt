package org.witness.proofmode.plugins.wallet.infra.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Asks each chain's ZeroDev paymaster, keylessly, whether it would sponsor a UserOp for the
 * project. It sends the SDK's own first paymaster call with a synthetic UserOp, so the answer
 * matches what a real send would get. The request is the one recorded in
 * `scripts/zerodev-probe-sponsorship.sh`; keep the two in step.
 *
 * Before probing it asks which chains the project has enabled and skips the rest. Enabled is
 * not sponsored, so that list only narrows the probes; it never classifies a chain as sponsored.
 */
class ZeroDevSponsorshipProbe(
    private val client: OkHttpClient = defaultClient(),
    private val prefilterTimeoutMs: Long = PREFILTER_TIMEOUT_MS,
) {

    /**
     * The entries of [chainIds] that are sponsored, in input order, or null when every probe was
     * inconclusive. A failed network must not read as "nothing is sponsored", so the caller keeps
     * its cache on null. An empty probe set (the project enables none of the given chains) or any
     * real answer gives a list, possibly empty.
     */
    suspend fun probe(
        projectId: String,
        chainIds: List<String>,
    ): List<String>? = coroutineScope {
        // The pre-filter GET is blocking too: cancelling the caller does not cut it short, it ends
        // at prefilterTimeoutMs and withContext then throws, so no probe is sent after it.
        val enabled = withContext(Dispatchers.IO) { fetchEnabledChainIds(projectId) }
        // A null pre-filter answer fails open: every given id is probed.
        val toProbe = if (enabled == null) chainIds else chainIds.filter { it in enabled }
        if (enabled != null) {
            Timber.tag(TAG).i(
                "Sponsorship pre-filter: given=%d probed=%d excluded=%s",
                chainIds.size,
                toProbe.size,
                chainIds.filter { it !in toProbe },
            )
        }

        // Blocking execute() is not throttled by OkHttp's five-per-host enqueue cap, so every
        // chain runs at once. A cancelled call is not interrupted; it ends at callTimeout and
        // its answer is dropped.
        val probed = toProbe
            .map { chainId -> async(Dispatchers.IO) { chainId to probeOne(projectId, chainId) } }
            .awaitAll()
        if (probed.isNotEmpty() && probed.all { it.second == SponsorshipClassification.UNKNOWN }) {
            return@coroutineScope null
        }
        probed.filter { it.second == SponsorshipClassification.SPONSORED }.map { it.first }
    }

    /**
     * The project's enabled chains as CAIP-2 ids, or null when the answer is not fully
     * understood. Null means probe everything: a changed response shape must never hide chains.
     * Blocking.
     */
    private fun fetchEnabledChainIds(projectId: String): Set<String>? {
        val body = fetchProjectBody(projectId) ?: return null
        val enabled = parseEnabledChainIds(body)
        if (enabled == null) Timber.tag(TAG).i("Sponsorship pre-filter unavailable: unexpected body")
        return enabled
    }

    /** The pre-filter's 2xx response body, or null for no usable response. Blocking. */
    private fun fetchProjectBody(projectId: String): String? {
        val url = ZeroDevConfigResolver.zeroDevProjectUrl(projectId).toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(url).header("accept", "application/json").get().build()
        val call = client.newBuilder().callTimeout(prefilterTimeoutMs, TimeUnit.MILLISECONDS).build()
            .newCall(request)
        return try {
            call.execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    Timber.tag(TAG).i("Sponsorship pre-filter unavailable: http=%d", response.code)
                    null
                }
            }
        } catch (e: IOException) {
            Timber.tag(TAG).i("Sponsorship pre-filter unavailable: transport=%s", e.javaClass.simpleName)
            null
        }
    }

    // org.json coerces: optLong turns "1" and 1.5 into 1 and optString turns null into "null".
    // Reading the raw element types instead keeps a renamed or stringified chain_id failing open.
    private fun parseEnabledChainIds(body: String?): Set<String>? {
        if (body.isNullOrEmpty()) return null
        return try {
            val chains = JSONObject(body).opt("chains") as? JSONArray ?: return null
            val ids = LinkedHashSet<String>()
            for (i in 0 until chains.length()) {
                val chain = chains.opt(i) as? JSONObject ?: return null
                val id = when (val raw = chain.opt("chain_id")) {
                    is Int -> raw.toLong()
                    is Long -> raw
                    else -> return null
                }
                if (id <= 0) return null
                ids += "eip155:$id"
            }
            ids
        } catch (e: JSONException) {
            null
        }
    }

    // A malformed id throws and fails the whole probe: the only caller passes SUPPORTED_CHAINS.
    private fun probeOne(projectId: String, caip2ChainId: String): SponsorshipClassification {
        val chainNumber = caip2ChainId.substringAfter(':').toLong()
        val url = ZeroDevConfigResolver.zeroDevRpcUrl(projectId, caip2ChainId).toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .post(requestBody(chainNumber).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        // The no-policy answer comes with HTTP 400, so the body is read whatever the status.
        val body = try {
            client.newCall(request).execute().use { it.body?.string() }
        } catch (e: IOException) {
            Timber.tag(TAG).i("Sponsorship probe: chain=%s class=UNKNOWN transport=%s", caip2ChainId, e.javaClass.simpleName)
            return SponsorshipClassification.UNKNOWN
        }

        val classification = SponsorshipClassifier.classify(body)
        Timber.tag(TAG).i("Sponsorship probe: chain=%s class=%s", caip2ChainId, classification)
        return classification
    }

    companion object {
        private const val TAG = "SponsorshipProbe"
        private const val CALL_TIMEOUT_SECONDS = 10L
        private const val PREFILTER_TIMEOUT_MS = 5_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val ENTRY_POINT_V07 = "0x0000000071727De22E5E9d8BAf0edAc6f37da032"
        private const val KERNEL_V3_3_DELEGATE = "0xd6CEDDe84be40893d153Be9d467CD6aD37875b28"

        // Never used on any chain, so nonce 0 everywhere; a precompile here breaks simulation.
        private const val PLACEHOLDER_SENDER = "0x1111111111111111111111111111111111111111"
        private val WORD_ONE = "0x" + "0".repeat(63) + "1"

        // r = 1, s = 1, v = 27: well-formed but wrong, so Kernel reports a signature failure
        // instead of reverting. An r above the curve order reverts with InvalidSignature().
        private val WRONG_SIGNATURE = "0x" + "0".repeat(63) + "1" + "0".repeat(63) + "1" + "1b"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        private fun requestBody(chainNumber: Long): String {
            val chainHex = "0x" + chainNumber.toString(16)
            // The bundler applies the delegate's code in simulation without checking this
            // authorization's signature, which gets simulation past "AA20 account not deployed".
            val authorization = JSONObject()
                .put("chainId", chainHex)
                .put("address", KERNEL_V3_3_DELEGATE)
                .put("nonce", "0x0")
                .put("yParity", "0x0")
                .put("r", WORD_ONE)
                .put("s", WORD_ONE)
            val userOp = JSONObject()
                .put("sender", PLACEHOLDER_SENDER)
                .put("nonce", "0x0")
                .put("callData", "0x")
                .put("callGasLimit", "0x0")
                .put("verificationGasLimit", "0x0")
                .put("preVerificationGas", "0x0")
                .put("maxFeePerGas", "0x0")
                .put("maxPriorityFeePerGas", "0x0")
                .put("signature", WRONG_SIGNATURE)
                .put("eip7702Auth", authorization)
            return JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "pm_getPaymasterStubData")
                .put("params", JSONArray().put(userOp).put(ENTRY_POINT_V07).put(chainHex).put(JSONObject()))
                .toString()
        }
    }
}
