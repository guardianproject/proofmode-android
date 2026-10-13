package org.witness.proofmode.plugins.lp.wallet.auth.components

import org.witness.proofmode.plugins.wallet.infra.exception.WalletNetworkUnavailableException

internal object SendCodeErrorMessages {

    private const val MAX_CAUSE_DEPTH = 10

    fun userFacing(
        cause: Throwable?,
        fallback: String,
        tooManyRequests: String,
        network: String,
    ): String {
        val chain = generateSequence(cause) { it.cause?.takeIf { c -> c !== it } }
            .take(MAX_CAUSE_DEPTH)
            .toList()
        val blob = chain.mapNotNull { it.message }.joinToString("\n")
        if (
            blob.contains("too many requests", ignoreCase = true) ||
            blob.contains("too_many_requests", ignoreCase = true)
        ) {
            return tooManyRequests
        }
        if (chain.any { it is WalletNetworkUnavailableException }) return network
        return fallback
    }
}
