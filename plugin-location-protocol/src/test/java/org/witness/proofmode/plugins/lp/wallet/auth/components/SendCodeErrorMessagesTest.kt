package org.witness.proofmode.plugins.lp.wallet.auth.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNetworkUnavailableException

class SendCodeErrorMessagesTest {

    @Test
    fun tooManyRequests_usesWaitCopy() {
        val error = WalletAuthException("Too many requests. Please wait to try again.")
        assertEquals(
            "Too many requests. Please wait to try again.",
            SendCodeErrorMessages.userFacing(
                cause = error,
                fallback = "Unable to send code",
                tooManyRequests = "Too many requests. Please wait to try again.",
                network = "No internet connection",
            ),
        )
    }

    @Test
    fun nestedTooManyRequests_usesWaitCopy() {
        val nested = WalletAuthException(
            "Failed to send email code",
            WalletAuthException("Too many requests. Please wait to try again."),
        )
        assertEquals(
            "Too many requests. Please wait to try again.",
            SendCodeErrorMessages.userFacing(
                cause = nested,
                fallback = "Unable to send code",
                tooManyRequests = "Too many requests. Please wait to try again.",
                network = "No internet connection",
            ),
        )
    }

    @Test
    fun otherErrors_keepGenericFallback() {
        val error = WalletAuthException("Failed to send email code")
        assertEquals(
            "Unable to send code",
            SendCodeErrorMessages.userFacing(
                cause = error,
                fallback = "Unable to send code",
                tooManyRequests = "Too many requests. Please wait to try again.",
                network = "No internet connection",
            ),
        )
    }

    @Test
    fun `network unavailable maps to network text`() {
        assertEquals(
            "N",
            SendCodeErrorMessages.userFacing(
                cause = WalletNetworkUnavailableException(),
                fallback = "F",
                tooManyRequests = "T",
                network = "N",
            ),
        )
    }

    @Test
    fun `nested network unavailable maps to network text`() {
        assertEquals(
            "N",
            SendCodeErrorMessages.userFacing(
                cause = RuntimeException("wrapper", WalletNetworkUnavailableException()),
                fallback = "F",
                tooManyRequests = "T",
                network = "N",
            ),
        )
    }
}
