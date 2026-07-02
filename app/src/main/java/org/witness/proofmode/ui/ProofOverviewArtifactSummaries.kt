package org.witness.proofmode.ui

import org.json.JSONObject
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.lp.config.easScanAttestationUrl

object ProofOverviewArtifactSummaries {

    data class LpCollapsedSummary(
        val uidShort: String?,
        val chainDisplayName: String?,
        val attesterShort: String? = null,
    )

    fun cidCollapsedSummary(json: String): String? =
        runCatching { JSONObject(json).optString("rootCid").takeIf { it.isNotBlank() } }.getOrNull()

    fun offchainCollapsedSummary(json: String): LpCollapsedSummary =
        parseLpSummary(json, includeAttester = true)

    fun onchainCollapsedSummary(json: String): LpCollapsedSummary =
        parseLpSummary(json, includeAttester = false)

    fun easScanUrlForArtifact(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val uid = root.optUid() ?: return null
        val chainId = root.optChainId() ?: return null
        val chain = SUPPORTED_CHAINS.find { it.caip2Id == chainId } ?: return null
        return chain.easScanAttestationUrl(uid)
    }

    fun formatArtifactJson(raw: String): String =
        runCatching { JSONObject(raw).toString(2) }.getOrElse { raw }

    private fun parseLpSummary(json: String, includeAttester: Boolean): LpCollapsedSummary {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return LpCollapsedSummary(null, null, null)

        val uid = root.optUid()
        val chainId = root.optChainId()
        val attester = if (includeAttester) root.optAttester() else null

        return LpCollapsedSummary(
            uidShort = uid?.let(::truncateHex),
            chainDisplayName = resolveChainDisplayName(chainId),
            attesterShort = attester?.let(::truncateHex),
        )
    }

    private fun JSONObject.optUid(): String? =
        sequenceOf("attestationUid", "uid")
            .map { optString(it) }
            .firstOrNull { it.isNotBlank() }

    private fun JSONObject.optChainId(): String? =
        optString("chainId").takeIf { it.isNotBlank() }

    private fun JSONObject.optAttester(): String? =
        sequenceOf("attesterAddress", "attester", "from")
            .map { optString(it) }
            .firstOrNull { it.isNotBlank() }
            ?: optJSONObject("message")?.optString("attester")?.takeIf { it.isNotBlank() }

    private fun resolveChainDisplayName(chainId: String?): String? =
        chainId?.let { id -> SUPPORTED_CHAINS.find { it.caip2Id == id }?.displayName }

    private fun truncateHex(value: String): String {
        if (value.length <= 12) return value
        if (value.length >= 40) return "${value.take(10)}…"
        if (value.length > 22) return "${value.take(6)}…${value.takeLast(4)}"
        return "${value.take(10)}…"
    }
}
