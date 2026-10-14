package org.witness.proofmode.plugins.lp.wallet.auth.components

import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletInvalidCodeException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNetworkUnavailableException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSignedInWithoutWalletException

/** Result of verifying an OTP code, as surfaced to [PrivyOtpView]. */
sealed interface VerifyOutcome {
    /** Code accepted and a wallet is ready. */
    data object Verified : VerifyOutcome

    /** Code accepted and the user is signed in, but the wallet is not (yet) available. */
    data object SignedInWithoutWallet : VerifyOutcome

    /** Verification failed; [message] is safe to show to the user. */
    data class Failed(val message: String) : VerifyOutcome

    companion object {
        /**
         * Maps a login failure to an outcome by type. Raw exception messages are never
         * exposed; anything unrecognized becomes [Failed] with [fallback].
         */
        fun from(
            t: Throwable,
            invalidCode: String,
            network: String,
            tooManyRequests: String,
            fallback: String,
        ): VerifyOutcome =
            when (t) {
                is WalletSignedInWithoutWalletException -> SignedInWithoutWallet
                is WalletInvalidCodeException -> Failed(invalidCode)
                is WalletNetworkUnavailableException -> Failed(network)
                is WalletAuthException ->
                    if (t.message?.contains("too many requests", ignoreCase = true) == true) {
                        Failed(tooManyRequests)
                    } else {
                        Failed(fallback)
                    }
                else -> Failed(fallback)
            }
    }
}
