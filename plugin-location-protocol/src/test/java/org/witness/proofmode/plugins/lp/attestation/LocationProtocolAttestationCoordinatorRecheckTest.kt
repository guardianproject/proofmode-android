package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationProtocolAttestationCoordinatorRecheckTest {

    private lateinit var storage: TempDirStorageProvider

    private val sepolia = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:11155111" }

    // Every field a pending record carries, so a rebuilt submit result equals this one.
    private val submitResult = OnchainSubmitResult(
        txHash = "0xtx",
        easAddress = EAS_ADDRESS,
        chainIdStr = sepolia.caip2Id,
        rpcUrls = sepolia.rpcUrls,
        submittedAt = 1L,
        sponsorshipActive = true,
        onChainAttester = "0xattester",
    )

    @Before
    fun setUp() {
        storage = TempDirStorageProvider.inNewTempDir()
    }

    @After
    fun tearDown() {
        storage.deleteRoot()
    }

    private fun managerAnswering(result: (method: String) -> String) = EASAttestationManager(
        bridgeProvider = { throw UnsupportedOperationException() },
        walletSigner = mock(),
        transactionSender = mock(),
        walletConnector = mock(),
        jsonRpcClient = EthJsonRpcClient(methodRoutedRpcClient(result)),
    )

    private fun coordinatorWith(manager: EASAttestationManager) =
        LocationProtocolAttestationCoordinator(storage, manager)

    private fun seedPending(manager: EASAttestationManager, submit: OnchainSubmitResult = submitResult) {
        storage.seed(HASH, PENDING_ID, manager.buildPendingAttestationResult(submit).offchainPayloadJson)
    }

    private fun rpc(receipt: String, transaction: String = "null"): (String) -> String = { method ->
        when (method) {
            "eth_getTransactionReceipt" -> receipt
            "eth_getTransactionByHash" -> transaction
            "eth_getBlockByNumber" -> """{"timestamp":"0x64"}"""
            else -> "null"
        }
    }

    @Test
    fun recheckPending_receiptConfirmed_writesConfirmed_deletesPending() = runTest {
        val manager = managerAnswering(rpc(receiptJson("0x1", EAS_ADDRESS, UID_WORD)))
        seedPending(manager)

        val outcome = coordinatorWith(manager).recheckPendingOnchain(HASH)

        assertTrue(outcome is PendingOnchainRecheckOutcome.Confirmed)
        assertFalse(storage.exists(HASH, PENDING_ID))
        val written = JSONObject(storage.read(HASH, CONFIRMED_ID)!!)
        val submitPath = manager.confirmOnchainLocationAttestation(submitResult)
            as OnchainConfirmationOutcome.Confirmed
        val built = JSONObject(submitPath.result.offchainPayloadJson)
        written.remove("confirmedAt")
        built.remove("confirmedAt")
        assertEquals(built.toString(), written.toString())
    }

    @Test
    fun recheckPending_resubmitOrSkipTable() = runTest {
        // (label, rpc replies) -> (outcome, pending record kept)
        val rows = listOf(
            Triple("reverted", rpc(receiptJson("0x0", EAS_ADDRESS, UID_WORD)), PendingOnchainRecheckOutcome.Resubmit to false),
            Triple("txNotFound", rpc(receipt = "null", transaction = "null"), PendingOnchainRecheckOutcome.Resubmit to false),
            Triple(
                "minedWithoutReceipt",
                rpc(receipt = "null", transaction = """{"hash":"0xtx"}"""),
                PendingOnchainRecheckOutcome.StillPending to true,
            ),
        )
        for ((label, replies, expected) in rows) {
            val manager = managerAnswering(replies)
            seedPending(manager)

            val outcome = coordinatorWith(manager).recheckPendingOnchain(HASH)

            assertEquals(label, expected.first, outcome)
            assertEquals(label, expected.second, storage.exists(HASH, PENDING_ID))
            assertNull(label, storage.read(HASH, CONFIRMED_ID))
        }
    }

    @Test
    fun recheckPending_unknownChain_keepsPending_skips() = runTest {
        val methods = mutableListOf<String>()
        val manager = managerAnswering { method -> methods += method; "null" }
        seedPending(manager, submitResult.copy(chainIdStr = "eip155:999999"))

        val outcome = coordinatorWith(manager).recheckPendingOnchain(HASH)

        assertEquals(PendingOnchainRecheckOutcome.StillPending, outcome)
        assertTrue(storage.exists(HASH, PENDING_ID))
        assertEquals(emptyList<String>(), methods)
    }

    private companion object {
        const val HASH = "hash1"
        const val PENDING_ID = HASH + LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX
        const val CONFIRMED_ID = HASH + LocationProtocolArtifactStore.ONCHAIN_SUFFIX
        const val EAS_ADDRESS = TEST_EAS_ADDRESS
        val UID_WORD = "ab".repeat(32)
    }
}
