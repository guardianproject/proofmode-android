package org.witness.proofmode.plugins.lp.wallet.auth.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationTimedOutException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletInvalidCodeException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNetworkUnavailableException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSignedInWithoutWalletException

class VerifyOutcomeTest {

    private fun map(t: Throwable) = VerifyOutcome.from(
        t,
        invalidCode = "INVALID",
        network = "NETWORK",
        tooManyRequests = "RATE",
        fallback = "FALLBACK",
    )

    @Test
    fun `signed in without wallet maps to SignedInWithoutWallet`() {
        val outcome = map(WalletSignedInWithoutWalletException(WalletCreationTimedOutException()))
        assertSame(VerifyOutcome.SignedInWithoutWallet, outcome)
    }

    @Test
    fun `invalid code maps to Failed with invalidCode text`() {
        assertEquals(VerifyOutcome.Failed("INVALID"), map(WalletInvalidCodeException()))
    }

    @Test
    fun `network unavailable maps to Failed with network text`() {
        assertEquals(VerifyOutcome.Failed("NETWORK"), map(WalletNetworkUnavailableException()))
    }

    @Test
    fun `rate limited maps to Failed with tooManyRequests text`() {
        assertEquals(VerifyOutcome.Failed("RATE"), map(WalletAuthException("Too many requests")))
    }

    @Test
    fun `other exception maps to Failed with fallback text`() {
        assertEquals(VerifyOutcome.Failed("FALLBACK"), map(WalletAuthException("raw secret detail")))
        assertEquals(VerifyOutcome.Failed("FALLBACK"), map(IllegalStateException("boom")))
    }
}
