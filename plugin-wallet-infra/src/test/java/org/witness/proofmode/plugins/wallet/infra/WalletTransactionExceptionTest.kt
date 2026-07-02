package org.witness.proofmode.plugins.wallet.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.exception.WalletChainMismatchException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionRejectedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionTimeoutException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletUnsupportedCapabilityException

class WalletTransactionExceptionTest {

    @Test
    fun `WalletUnsupportedCapabilityException is a WalletTransactionException and WalletException`() {
        val ex = WalletUnsupportedCapabilityException("sendTransaction")
        assertTrue(ex is WalletTransactionException)
        assertTrue(ex is WalletException)
        assertTrue(ex.message!!.contains("sendTransaction"))
    }

    @Test
    fun `WalletUnsupportedCapabilityException message contains capability name`() {
        val ex = WalletUnsupportedCapabilityException("signTypedData")
        assertEquals("Unsupported capability: signTypedData", ex.message)
    }

    @Test
    fun `WalletTransactionRejectedException is a WalletTransactionException and WalletException`() {
        val ex = WalletTransactionRejectedException("User rejected")
        assertTrue(ex is WalletTransactionException)
        assertTrue(ex is WalletException)
        assertEquals("User rejected", ex.message)
    }

    @Test
    fun `WalletTransactionRejectedException preserves cause`() {
        val cause = RuntimeException("original")
        val ex = WalletTransactionRejectedException("Rejected", cause)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `WalletTransactionTimeoutException is a WalletTransactionException and WalletException`() {
        val ex = WalletTransactionTimeoutException()
        assertTrue(ex is WalletTransactionException)
        assertTrue(ex is WalletException)
        assertEquals("Transaction timed out", ex.message)
    }

    @Test
    fun `WalletTransactionTimeoutException accepts custom message`() {
        val ex = WalletTransactionTimeoutException("Timed out after 30s")
        assertEquals("Timed out after 30s", ex.message)
    }

    @Test
    fun `WalletChainMismatchException is a WalletTransactionException and WalletException`() {
        val ex = WalletChainMismatchException("mainnet", "goerli")
        assertTrue(ex is WalletTransactionException)
        assertTrue(ex is WalletException)
        assertTrue(ex.message!!.contains("mainnet"))
        assertTrue(ex.message!!.contains("goerli"))
    }

    @Test
    fun `WalletChainMismatchException message format is correct`() {
        val ex = WalletChainMismatchException("mainnet", "goerli")
        assertEquals("Chain mismatch: expected mainnet, got goerli", ex.message)
    }

    @Test
    fun `WalletProviderSubmitException is a WalletTransactionException and WalletException`() {
        val ex = WalletProviderSubmitException("RPC error")
        assertTrue(ex is WalletTransactionException)
        assertTrue(ex is WalletException)
        assertEquals("RPC error", ex.message)
    }

    @Test
    fun `WalletProviderSubmitException preserves cause`() {
        val cause = RuntimeException("network error")
        val ex = WalletProviderSubmitException("Submit failed", cause)
        assertNotNull(ex.cause)
        assertEquals("network error", ex.cause!!.message)
    }
}
