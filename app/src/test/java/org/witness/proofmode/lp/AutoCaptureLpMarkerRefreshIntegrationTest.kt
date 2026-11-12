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

class AutoCaptureLpMarkerRefreshIntegrationTest {

    private lateinit var storage: StorageProvider

    @Before
    fun setUp() {
        AutoCaptureLpStateRegistry.clearForTests()
        storage = mock()
    }

    @Test
    fun pendingArtifact_overridesRunningRegistry() {
        val state = AutoCaptureLpItemState(onchain = LpRunState.RUNNING)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.json")).thenReturn(false)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.pending.json")).thenReturn(true)

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, storage)
        assertEquals(LpOnchainBadge.PENDING, badges.onchain)
    }

    @Test
    fun confirmedArtifact_afterNotifyRefresh() {
        val state = AutoCaptureLpItemState(onchain = LpRunState.RUNNING)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.json")).thenReturn(true)

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, storage)
        assertEquals(LpOnchainBadge.CONFIRMED, badges.onchain)
    }
}
