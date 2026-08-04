package org.witness.proofmode.plugins.ipfscid

import org.junit.Test

class CidSidecarWriteOutcomeTest {
    @Test fun outcomeVariants_areExhaustive() {
        val outcomes: List<CidSidecarWriteOutcome> = listOf(
            CidSidecarWriteOutcome.SkippedGateOff,
            CidSidecarWriteOutcome.SkippedEmptyManifest,
            CidSidecarWriteOutcome.Success("bafyROOT"),
            CidSidecarWriteOutcome.Failed("OOM"),
        )
        outcomes.forEach { CidSidecarWriteOutcome.logAtBoundary("abc", it) }
    }
}
