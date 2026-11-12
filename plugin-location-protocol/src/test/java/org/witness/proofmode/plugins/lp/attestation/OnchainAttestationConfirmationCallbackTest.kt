package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.TestWalletStackReset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnchainAttestationConfirmationCallbackTest {

    private lateinit var storage: TempDirStorageProvider
    private lateinit var artifactStore: LocationProtocolArtifactStore
    private val easManager = mock<EASAttestationManager>()
    private val confirmed = mutableListOf<String>()
    private val failed = mutableListOf<String>()

    private val submitResult = OnchainSubmitResult(
        txHash = "0xabc",
        easAddress = "0xeas",
        chainIdStr = "eip155:11155111",
        rpcUrls = listOf("https://rpc.test"),
        submittedAt = 1L,
        sponsorshipActive = true,
        onChainAttester = "0xattester",
    )

    @Before
    fun setUp() {
        TestWalletStackReset.reset()
        LocationProtocolPlugin.registerApplicationScope(
            CoroutineScope(Dispatchers.Unconfined),
        )
        storage = TempDirStorageProvider.inNewTempDir()
        artifactStore = LocationProtocolArtifactStore(storage)
        storage.seed(HASH, PENDING_ID, "{}")
    }

    @After
    fun tearDown() {
        storage.deleteRoot()
    }

    private suspend fun scheduleWith(outcome: OnchainConfirmationOutcome) {
        whenever(easManager.confirmOnchainLocationAttestation(submitResult)).thenReturn(outcome)
        OnchainAttestationConfirmation.schedule(
            mediaHash = HASH,
            submitResult = submitResult,
            easManager = easManager,
            artifactStore = artifactStore,
            onConfirmed = { confirmed += it },
            onFailed = { failed += it },
        )
    }

    @Test
    fun confirmation_success_deletesPendingRecord() = runTest {
        val confirmedPayload = """{"status":"confirmed"}"""
        scheduleWith(
            OnchainConfirmationOutcome.Confirmed(
                LocationProtocolAttestationResult(
                    uid = "uid1",
                    attesterAddress = "0xattester",
                    timestamp = 1L,
                    offchainPayloadJson = confirmedPayload,
                ),
            ),
        )

        assertEquals(confirmedPayload, storage.read(HASH, CONFIRMED_ID))
        assertFalse(storage.exists(HASH, PENDING_ID))
        assertEquals(listOf(HASH), confirmed)
        assertEquals(emptyList<String>(), failed)
    }

    @Test
    fun confirmation_reverted_deletesPending_reportsFailed() = runTest {
        scheduleWith(OnchainConfirmationOutcome.Reverted)

        assertFalse(storage.exists(HASH, PENDING_ID))
        assertNull(storage.read(HASH, CONFIRMED_ID))
        assertEquals(listOf(HASH), failed)
        assertEquals(emptyList<String>(), confirmed)
    }

    @Test
    fun confirmation_failed_keepsPending_writesNoConfirmed_reportsFailed() = runTest {
        scheduleWith(OnchainConfirmationOutcome.Failed(Exception("receipt poll timed out")))

        assertTrue(storage.exists(HASH, PENDING_ID))
        assertNull(storage.read(HASH, CONFIRMED_ID))
        assertEquals(listOf(HASH), failed)
        assertEquals(emptyList<String>(), confirmed)
    }

    private companion object {
        const val HASH = "hash1"
        const val PENDING_ID = HASH + LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX
        const val CONFIRMED_ID = HASH + LocationProtocolArtifactStore.ONCHAIN_SUFFIX
    }
}
