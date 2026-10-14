package org.witness.proofmode.plugins.wallet.infra.privy

import io.privy.network.ApiResult
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationFailedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletInvalidCodeException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNetworkUnavailableException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSessionExpiredException

/**
 * Maps Privy SDK failures onto typed [WalletException]s so callers can show accurate messages
 * instead of treating every failure as an invalid verification code.
 */
internal object PrivyErrorMapper {

    private const val MAX_CAUSE_DEPTH = 10
    private const val INVALID_CREDENTIALS = "invalid_credentials"

    fun mapLoginError(t: Throwable): WalletException {
        if (t is WalletException) return t
        if (isNetworkError(t)) return WalletNetworkUnavailableException(t)
        val root = findRoot(t)
        if (root is PrivyApiException) {
            val status = root.statusCode
            if (root.responseBody?.code == INVALID_CREDENTIALS || status == 422) {
                return WalletInvalidCodeException(t)
            }
            if (status == 429) return WalletAuthException("too many requests", t)
        }
        return WalletAuthException(t.message ?: "Privy authentication failed", t)
    }

    fun mapCreationError(t: Throwable): WalletException {
        if (t is WalletException) return t
        if (isNetworkError(t)) return WalletNetworkUnavailableException(t)
        val root = findRoot(t)
        if (root is PrivyApiException && root.statusCode == 401) return WalletSessionExpiredException(t)
        return WalletCreationFailedException(cause = t)
    }

    /**
     * True when the cause chain holds a [NoNetworkException] or the SDK 0.16 offline sentinel
     * ([ApiResult.Error.NoNetworkError.exception], usually wrapped in a [PrivyApiException]
     * with no status code).
     */
    fun isNetworkError(t: Throwable): Boolean =
        causeChain(t).any { it is NoNetworkException || it === ApiResult.Error.NoNetworkError.exception }

    /** First [PrivyApiException] or [NoNetworkException] in the cause chain (cycle-safe, bounded). */
    private fun findRoot(t: Throwable): Throwable? =
        causeChain(t).firstOrNull { it is PrivyApiException || it is NoNetworkException }

    private fun causeChain(t: Throwable): Sequence<Throwable> =
        generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(MAX_CAUSE_DEPTH)
}
