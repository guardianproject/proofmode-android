package org.witness.proofmode.lp

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpItemState
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.plugins.lp.autocapture.LpRunState
import org.witness.proofmode.storage.StorageProvider

class AutoCaptureLpMarkerRefreshIntegrationTest {

    private lateinit var storage: StorageProvider
    private lateinit var artifactStore: LocationProtocolArtifactStore

    @Before
    fun setUp() {
        AutoCaptureLpStateRegistry.clearForTests()
        storage = mock()
        artifactStore = mock()
    }

    @Test
    fun pendingArtifact_overridesRunningRegistry() {
        val state = AutoCaptureLpItemState(onchain = LpRunState.RUNNING)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.json")).thenReturn(false)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.pending.json")).thenReturn(true)
        whenever(artifactStore.readPendingOnchainAttestation("hash1")).thenReturn("""{"pending":true}""")

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, artifactStore, storage)
        assertEquals(LpOnchainBadge.PENDING, badges.onchain)
    }

    @Test
    fun confirmedArtifact_afterNotifyRefresh() {
        val state = AutoCaptureLpItemState(onchain = LpRunState.RUNNING)
        whenever(storage.proofIdentifierExists("hash1", "hash1.lp.onchain.json")).thenReturn(true)
        whenever(artifactStore.readOnchainAttestation("hash1")).thenReturn("""{"confirmed":true}""")

        val badges = AutoCaptureLpMarkerResolver.resolve("hash1", state, artifactStore, storage)
        assertEquals(LpOnchainBadge.CONFIRMED, badges.onchain)
    }
}
