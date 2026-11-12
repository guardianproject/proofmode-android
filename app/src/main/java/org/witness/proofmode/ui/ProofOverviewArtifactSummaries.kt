package org.witness.proofmode.ui

import org.json.JSONObject
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.lp.config.easScanAttestationUrl
import org.witness.proofmode.plugins.lp.config.explorerTransactionUrl

object ProofOverviewArtifactSummaries {

    fun chainDisplayName(json: String): String? =
        runCatching { JSONObject(json) }.getOrNull()?.optChain()?.displayName

    fun easScanUrlForArtifact(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val uid = root.optUid() ?: return null
        val chain = root.optChain() ?: return null
        return chain.easScanAttestationUrl(uid)
    }

    fun transactionUrlForArtifact(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val txHash = root.optString("txHash").trim().takeIf { it.isNotEmpty() } ?: return null
        val chain = root.optChain() ?: return null
        return chain.explorerTransactionUrl(txHash)
    }

    // org.json's optString turns a JSON null into the text "null", so null is checked first.
    fun rootCidForArtifact(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.isNull("rootCid")) return null
        return root.optString("rootCid").takeIf { it.isNotBlank() }
    }

    fun formatArtifactJson(raw: String): String =
        runCatching { JSONObject(raw).toString(2) }.getOrElse { raw }

    private fun JSONObject.optUid(): String? =
        sequenceOf("attestationUid", "uid")
            .map { optString(it) }
            .firstOrNull { it.isNotBlank() }

    private fun JSONObject.optChain() =
        optString("chainId").takeIf { it.isNotBlank() }
            ?.let { id -> SUPPORTED_CHAINS.find { it.caip2Id == id } }
}
