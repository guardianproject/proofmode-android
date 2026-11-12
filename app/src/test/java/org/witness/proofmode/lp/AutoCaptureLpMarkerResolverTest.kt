package org.witness.proofmode.lp

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpItemState
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.plugins.lp.autocapture.LpRunState
import org.witness.proofmode.storage.StorageProvider

class AutoCaptureLpMarkerResolverTest {

    private lateinit var storage: StorageProvider

    @Before
    fun setUp() {
        AutoCaptureLpStateRegistry.clearForTests()
        storage = mock()
    }

    @Test
    fun offchainRunning_showsSpinner() {
        val state = AutoCaptureLpItemState(offchain = LpRunState.RUNNING)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.offchain.json")).thenReturn(false)

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, storage)
        assertEquals(LpOffchainBadge.SPINNER, badges.offchain)
    }

    @Test
    fun offchainArtifact_showsFinal() {
        val state = AutoCaptureLpItemState(offchain = LpRunState.SUCCEEDED)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.offchain.json")).thenReturn(true)

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, storage)
        assertEquals(LpOffchainBadge.FINAL, badges.offchain)
    }

    @Test
    fun onchainPending_overridesRegistrySucceeded() {
        val state = AutoCaptureLpItemState(onchain = LpRunState.SUCCEEDED)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.json")).thenReturn(false)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.pending.json")).thenReturn(true)

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, storage)
        assertEquals(LpOnchainBadge.PENDING, badges.onchain)
    }

    @Test
    fun onchainConfirmed_overridesPending() {
        val state = AutoCaptureLpItemState(onchain = LpRunState.RUNNING)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.json")).thenReturn(true)

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, storage)
        assertEquals(LpOnchainBadge.CONFIRMED, badges.onchain)
    }

    @Test
    fun onchainRunning_showsSpinnerWhenNoArtifact() {
        val state = AutoCaptureLpItemState(onchain = LpRunState.RUNNING)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.json")).thenReturn(false)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.pending.json")).thenReturn(false)

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, storage)
        assertEquals(LpOnchainBadge.SPINNER, badges.onchain)
    }
}
