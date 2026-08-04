package org.witness.proofmode.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LpActiveBadgeVisibilityTest {
    @Test
    fun badgeHiddenWhenLpActiveFalse() {
        assertFalse(lpBadgeAllowed(lpActive = false, proofStatus = ProofStatus.GENERATED))
    }

    @Test
    fun badgeAllowedWhenLpActiveAndGenerated() {
        assertTrue(lpBadgeAllowed(lpActive = true, proofStatus = ProofStatus.GENERATED))
    }

    @Test
    fun badgeHiddenWhenNotGenerated() {
        assertFalse(lpBadgeAllowed(lpActive = true, proofStatus = ProofStatus.PENDING))
    }
}
