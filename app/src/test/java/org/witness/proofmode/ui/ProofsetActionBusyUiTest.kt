package org.witness.proofmode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.lp.autocapture.LpRunState

class ProofsetActionBusyUiTest {
    @Test
    fun filebaseBar_busyWhenIssuingOrInSlot() {
        assertTrue(filebaseActionBarBusy(inSlot = false, issuing = true))
        assertTrue(filebaseActionBarBusy(inSlot = true, issuing = false))
        assertFalse(filebaseActionBarBusy(inSlot = false, issuing = false))
    }

    @Test
    fun lpBar_busyOnlyWhileRunningWithoutArtifact() {
        assertTrue(lpActionBarBusy(LpRunState.RUNNING, hasArtifact = false))
        assertFalse(lpActionBarBusy(LpRunState.RUNNING, hasArtifact = true))
        assertFalse(lpActionBarBusy(LpRunState.IDLE, hasArtifact = false))
        assertFalse(lpActionBarBusy(LpRunState.SUCCEEDED, hasArtifact = true))
        assertFalse(lpActionBarBusy(LpRunState.FAILED, hasArtifact = false))
        assertFalse(lpActionBarBusy(LpRunState.SKIPPED, hasArtifact = false))
    }

    @Test
    fun filebaseMetadata_pendingThenReady() {
        assertEquals(MetadataPhase.HIDDEN, filebaseMetadataPhase(occupancy = 0, hasLinks = false))
        assertEquals(MetadataPhase.PENDING, filebaseMetadataPhase(occupancy = 1, hasLinks = false))
        assertEquals(MetadataPhase.READY, filebaseMetadataPhase(occupancy = 1, hasLinks = true))
        assertEquals(MetadataPhase.READY, filebaseMetadataPhase(occupancy = 0, hasLinks = true))
    }

    @Test
    fun lpMetadata_pendingThenReady() {
        assertEquals(
            MetadataPhase.HIDDEN,
            lpMetadataPhase(LpRunState.IDLE, hasArtifact = false),
        )
        assertEquals(
            MetadataPhase.PENDING,
            lpMetadataPhase(LpRunState.RUNNING, hasArtifact = false),
        )
        assertEquals(
            MetadataPhase.READY,
            lpMetadataPhase(LpRunState.RUNNING, hasArtifact = true),
        )
        assertEquals(
            MetadataPhase.READY,
            lpMetadataPhase(LpRunState.SUCCEEDED, hasArtifact = true),
        )
        assertEquals(
            MetadataPhase.HIDDEN,
            lpMetadataPhase(LpRunState.FAILED, hasArtifact = false),
        )
    }
}
