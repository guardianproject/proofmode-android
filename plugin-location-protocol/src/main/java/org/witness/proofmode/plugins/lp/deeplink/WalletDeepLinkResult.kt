package org.witness.proofmode.plugins.lp.deeplink

data class WalletDeepLinkResult(
    val appliedChain: String? = null,
    val appliedSponsor: Boolean? = null,
    val appliedProjectId: String? = null,
    val skipped: List<String> = emptyList(),
    val rejected: Boolean = false,
    val userMessage: String? = null,
)
