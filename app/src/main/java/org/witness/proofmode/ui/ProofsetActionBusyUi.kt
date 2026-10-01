package org.witness.proofmode.ui

import org.witness.proofmode.plugins.lp.autocapture.LpRunState

enum class MetadataPhase { HIDDEN, PENDING, READY }

fun filebaseActionBarBusy(inSlot: Boolean, issuing: Boolean): Boolean = issuing || inSlot

fun lpActionBarBusy(runState: LpRunState, hasArtifact: Boolean): Boolean =
    runState == LpRunState.RUNNING && !hasArtifact

fun filebaseMetadataPhase(occupancy: Int, hasLinks: Boolean): MetadataPhase = when {
    hasLinks -> MetadataPhase.READY
    occupancy > 0 -> MetadataPhase.PENDING
    else -> MetadataPhase.HIDDEN
}

fun lpMetadataPhase(runState: LpRunState, hasArtifact: Boolean): MetadataPhase = when {
    hasArtifact -> MetadataPhase.READY
    runState == LpRunState.RUNNING -> MetadataPhase.PENDING
    else -> MetadataPhase.HIDDEN
}
