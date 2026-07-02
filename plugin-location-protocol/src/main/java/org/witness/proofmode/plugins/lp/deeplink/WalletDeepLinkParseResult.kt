package org.witness.proofmode.plugins.lp.deeplink

enum class ParamOutcome {
    ABSENT,
    VALID,
    INVALID,
}

data class ParsedParam<T>(
    val outcome: ParamOutcome,
    val value: T? = null,
)

data class WalletDeepLinkParseResult(
    val chain: ParsedParam<String>? = null,
    val sponsor: ParsedParam<Boolean>? = null,
    val projectId: ParsedParam<String>? = null,
    val host: String?,
    val isWalletRoute: Boolean,
)
