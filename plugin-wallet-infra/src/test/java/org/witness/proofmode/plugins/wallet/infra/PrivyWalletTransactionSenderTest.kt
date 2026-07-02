package org.witness.proofmode.plugins.wallet.infra

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender
import org.witness.proofmode.plugins.wallet.infra.exception.WalletChainMismatchException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNotInitializedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionRejectedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionTimeoutException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletUnsupportedCapabilityException
import org.witness.proofmode.plugins.wallet.infra.internal.SendTransactionErrorMapper

/**
 * Contract tests for sendTransaction semantics, verified via a FakeWalletTransactionSender
 * that mirrors the validation logic in PrivyWalletConnector without requiring Privy internals.
 */
class PrivyWalletTransactionSenderTest {

    // region Fake

    private class FakeWalletTransactionSender(
        private val activeChainId: String = "1",
        private val hasWallet: Boolean = true,
        private val simulateRejection: Boolean = false,
        private val simulateTimeout: Boolean = false,
        private val simulateProviderFailure: Boolean = false,
    ) : WalletTransactionSender {

        override suspend fun sendTransaction(params: Map<String, Any?>): Map<String, Any> {
            try {
                // Required field validation
                val requiredFields = listOf("to", "data", "valueHex", "chainId")
                for (key in requiredFields) {
                    if (params[key] == null) {
                        throw WalletProviderSubmitException("Missing required field: $key")
                    }
                }

                // Chain validation
                val requestChainId = params["chainId"] as String
                if (requestChainId != activeChainId) {
                    throw WalletChainMismatchException(expected = activeChainId, actual = requestChainId)
                }

                // Wallet availability
                if (!hasWallet) {
                    throw WalletUnsupportedCapabilityException("send-transaction")
                }

                // Simulate provider outcomes
                if (simulateRejection) {
                    throw WalletTransactionRejectedException("User denied transaction signature")
                }
                if (simulateTimeout) {
                    throw WalletTransactionTimeoutException("Provider request timed out")
                }
                if (simulateProviderFailure) {
                    throw WalletProviderSubmitException("Provider returned error code -32000")
                }

                val txHash = "0xabc123"
                return mapOf(
                    "txHash" to txHash,
                    "chainId" to requestChainId,
                    "submittedAtMs" to System.currentTimeMillis(),
                )
            } catch (e: WalletTransactionException) {
                throw e
            } catch (e: Throwable) {
                throw WalletProviderSubmitException(e.message ?: "Unexpected error", e)
            }
        }
    }

    // endregion

    private fun validParams(chainId: String = "1") = mapOf(
        "to" to "0xRecipient",
        "data" to "0x",
        "valueHex" to "0x0",
        "chainId" to chainId,
    )

    // region Tests

    @Test
    fun `test 0 - mapper returns rejected exception`() {
        val mapped = SendTransactionErrorMapper.fromProviderError(
            message = "User denied transaction",
            cause = null,
        )
        assertTrue(mapped is WalletTransactionRejectedException)
    }

    @Test
    fun `test 0b - mapper returns timeout exception`() {
        val mapped = SendTransactionErrorMapper.fromProviderError(
            message = "request timed out",
            cause = null,
        )
        assertTrue(mapped is WalletTransactionTimeoutException)
    }

    @Test
    fun `test 0c - mapper returns provider submit exception`() {
        val mapped = SendTransactionErrorMapper.fromProviderError(
            message = "rpc -32000 insufficient funds",
            cause = null,
        )
        assertTrue(mapped is WalletProviderSubmitException)
    }

    @Test
    fun `test 1 - success returns txHash chainId submittedAtMs`() = runTest {
        val sender = FakeWalletTransactionSender(activeChainId = "1")
        val result = sender.sendTransaction(validParams("1"))

        assertEquals("0xabc123", result["txHash"])
        assertEquals("1", result["chainId"])
        assertNotNull(result["submittedAtMs"])
        assertTrue((result["submittedAtMs"] as Long) > 0L)
    }

    @Test
    fun `test 2 - missing required field 'to' throws WalletProviderSubmitException`() = runTest {
        val sender = FakeWalletTransactionSender()
        val params = mapOf(
            "data" to "0x",
            "valueHex" to "0x0",
            "chainId" to "1",
        )
        try {
            sender.sendTransaction(params)
            assert(false) { "Expected WalletProviderSubmitException" }
        } catch (e: WalletProviderSubmitException) {
            assertTrue(e.message!!.contains("Missing required field"))
        }
    }

    @Test
    fun `test 3 - missing required field 'chainId' throws WalletProviderSubmitException`() = runTest {
        val sender = FakeWalletTransactionSender()
        val params = mapOf(
            "to" to "0xRecipient",
            "data" to "0x",
            "valueHex" to "0x0",
        )
        try {
            sender.sendTransaction(params)
            assert(false) { "Expected WalletProviderSubmitException" }
        } catch (e: WalletProviderSubmitException) {
            assertTrue(e.message!!.contains("Missing required field"))
        }
    }

    @Test
    fun `test 4 - chain mismatch throws WalletChainMismatchException`() = runTest {
        val sender = FakeWalletTransactionSender(activeChainId = "1")
        try {
            sender.sendTransaction(validParams(chainId = "137"))
            assert(false) { "Expected WalletChainMismatchException" }
        } catch (e: WalletChainMismatchException) {
            assertTrue(e.message!!.contains("1"))
            assertTrue(e.message!!.contains("137"))
        }
    }

    @Test
    fun `test 5 - no wallet available throws WalletUnsupportedCapabilityException`() = runTest {
        val sender = FakeWalletTransactionSender(activeChainId = "1", hasWallet = false)
        try {
            sender.sendTransaction(validParams("1"))
            assert(false) { "Expected WalletUnsupportedCapabilityException" }
        } catch (e: WalletUnsupportedCapabilityException) {
            assertTrue(e.message!!.contains("send-transaction"))
        }
    }

    @Test
    fun `test 6 - provider rejection throws WalletTransactionRejectedException`() = runTest {
        val sender = FakeWalletTransactionSender(activeChainId = "1", simulateRejection = true)
        try {
            sender.sendTransaction(validParams("1"))
            assert(false) { "Expected WalletTransactionRejectedException" }
        } catch (e: WalletTransactionRejectedException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `test 7 - provider submit failure throws WalletProviderSubmitException`() = runTest {
        val sender = FakeWalletTransactionSender(activeChainId = "1", simulateProviderFailure = true)
        try {
            sender.sendTransaction(validParams("1"))
            assert(false) { "Expected WalletProviderSubmitException" }
        } catch (e: WalletProviderSubmitException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `test 8 - timeout throws WalletTransactionTimeoutException`() = runTest {
        val sender = FakeWalletTransactionSender(activeChainId = "1", simulateTimeout = true)
        try {
            sender.sendTransaction(validParams("1"))
            assert(false) { "Expected WalletTransactionTimeoutException" }
        } catch (e: WalletTransactionTimeoutException) {
            assertNotNull(e.message)
        }
    }

    // endregion
}
