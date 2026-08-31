package org.witness.proofmode.plugins.lp.wallet.auth.components

internal object SendCodeErrorMessages {

    fun userFacing(cause: Throwable?, fallback: String, tooManyRequests: String): String {
        val blob = generateSequence(cause) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")
        if (
            blob.contains("too many requests", ignoreCase = true) ||
            blob.contains("too_many_requests", ignoreCase = true)
        ) {
            return tooManyRequests
        }
        return fallback
    }
}
