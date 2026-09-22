package org.witness.proofmode.ui.status

import org.junit.Assert.assertEquals
import org.junit.Test
import org.witness.proofmode.c2pa.ValidationState
import org.witness.proofmode.ui.ProofStatus

class OverlayPriorityTest {
    @Test
    fun generatedUsesC2paTopStart() = assertEquals(
        OverlayPriority.C2PA,
        overlayPriority(ProofStatus.GENERATED, ValidationState.TRUSTED),
    )

    @Test
    fun generatedInvalidUsesNone() = assertEquals(
        OverlayPriority.NONE,
        overlayPriority(ProofStatus.GENERATED, ValidationState.INVALID),
    )

    @Test
    fun pendingAndGeneratingWinTopStart() {
        assertEquals(
            OverlayPriority.PENDING,
            overlayPriority(ProofStatus.PENDING, ValidationState.TRUSTED),
        )
        assertEquals(
            OverlayPriority.GENERATING,
            overlayPriority(ProofStatus.GENERATING, ValidationState.TRUSTED),
        )
    }
}
