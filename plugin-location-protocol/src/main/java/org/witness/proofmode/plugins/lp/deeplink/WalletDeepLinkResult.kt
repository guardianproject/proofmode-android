package org.witness.proofmode.plugins.lp.deeplink

data class WalletDeepLinkResult(
    val rejected: Boolean = false,
    val userMessage: String? = null,
)
