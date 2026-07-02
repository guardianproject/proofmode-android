package org.witness.proofmode.plugins.wallet.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionRejectedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionTimeoutException
import org.witness.proofmode.plugins.wallet.infra.internal.SendTransactionErrorMapper

class SendTransactionErrorMapperErc4337Test {

    @Test
    fun `maps AA21 error to user friendly sponsorship limit message`() {
        val cause = RuntimeException("aa21 error")
        val ex = SendTransactionErrorMapper.fromProviderError("Execution reverted: AA21 paymaster out of gas", cause)
        assertTrue(ex is WalletProviderSubmitException)
        assertEquals("Gas sponsorship limit reached. The paymaster cannot sponsor this transaction. Please wait or contact your administrator.", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `maps AA25 error to paymaster validation message`() {
        val cause = RuntimeException("aa25 error")
        val ex = SendTransactionErrorMapper.fromProviderError("AA25 paymaster validation failed", cause)
        assertTrue(ex is WalletProviderSubmitException)
        assertEquals("Paymaster validation failed. The transaction could not be verified by the gas sponsor.", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `maps AA30 and AA31 errors to paymaster unavailable message`() {
        val cause = RuntimeException("aa30 error")
        val ex1 = SendTransactionErrorMapper.fromProviderError("AA30 paymaster internal error", cause)
        assertTrue(ex1 is WalletProviderSubmitException)
        assertEquals("Paymaster is temporarily unavailable. Please try again later.", ex1.message)

        val ex2 = SendTransactionErrorMapper.fromProviderError("AA31 paymaster error", cause)
        assertTrue(ex2 is WalletProviderSubmitException)
        assertEquals("Paymaster is temporarily unavailable. Please try again later.", ex2.message)
    }

    @Test
    fun `maps AA40 and AA41 errors to smart account validation message`() {
        val cause = RuntimeException("aa40 error")
        val ex1 = SendTransactionErrorMapper.fromProviderError("AA40 signature validation failed", cause)
        assertTrue(ex1 is WalletProviderSubmitException)
        assertEquals("Smart account validation failed. The account signature could not be verified on-chain.", ex1.message)

        val ex2 = SendTransactionErrorMapper.fromProviderError("AA41 account error", cause)
        assertTrue(ex2 is WalletProviderSubmitException)
        assertEquals("Smart account validation failed. The account signature could not be verified on-chain.", ex2.message)
    }

    @Test
    fun `maps insufficient funds and insufficient gas errors to gas requirement message`() {
        val cause = RuntimeException("insufficient funds")
        val ex1 = SendTransactionErrorMapper.fromProviderError("insufficient funds for gas", cause)
        assertTrue(ex1 is WalletProviderSubmitException)
        assertEquals("Insufficient Gas: This operation requires native gas tokens (ETH) to execute. Add funds or contact your administrator.", ex1.message)

        val ex2 = SendTransactionErrorMapper.fromProviderError("insufficient_funds for gas", cause)
        assertTrue(ex2 is WalletProviderSubmitException)
        assertEquals("Insufficient Gas: This operation requires native gas tokens (ETH) to execute. Add funds or contact your administrator.", ex2.message)

        val ex3 = SendTransactionErrorMapper.fromProviderError("insufficient gas price", cause)
        assertTrue(ex3 is WalletProviderSubmitException)
        assertEquals("Insufficient Gas: This operation requires native gas tokens (ETH) to execute. Add funds or contact your administrator.", ex3.message)
    }

    @Test
    fun `retains rejected error mapping`() {
        val cause = RuntimeException("rejected")
        val ex = SendTransactionErrorMapper.fromProviderError("user rejected transaction", cause)
        assertTrue(ex is WalletTransactionRejectedException)
        assertEquals("user rejected transaction", ex.message)
    }

    @Test
    fun `retains timeout error mapping`() {
        val cause = RuntimeException("timeout")
        val ex = SendTransactionErrorMapper.fromProviderError("timed out waiting for transaction", cause)
        assertTrue(ex is WalletTransactionTimeoutException)
        assertEquals("timed out waiting for transaction", ex.message)
    }

    @Test
    fun `maps generic execution reverted to EAS guidance message`() {
        val cause = RuntimeException("revert")
        val ex = SendTransactionErrorMapper.fromProviderError(
            "Execution reverted for an unknown reason",
            cause,
        )
        assertTrue(ex is WalletProviderSubmitException)
        assertTrue(ex.message!!.contains("Sepolia testnet"))
    }

    @Test
    fun `maps SIGN_USEROP_FAILED paymaster stub error to sponsorship signing message`() {
        val cause = RuntimeException("SIGN_USEROP_FAILED (code 12): signing for paymaster stub failed")
        val ex = SendTransactionErrorMapper.fromProviderError(cause.message, cause)
        assertTrue(ex is WalletProviderSubmitException)
        assertTrue(ex.message!!.contains("Gas sponsorship signing failed"))
        assertTrue(ex.message!!.contains("Sepolia"))
    }

    @Test
    fun `falls back to generic exception for unknown errors`() {
        val cause = RuntimeException("unknown")
        val ex = SendTransactionErrorMapper.fromProviderError("some random RPC error", cause)
        assertTrue(ex is WalletProviderSubmitException)
        assertEquals("some random RPC error", ex.message)
    }
}
