package org.witness.proofmode.plugins.lp.wallet.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletOnboardingNavigationTest {

    @Test
    fun nextOnboardingPage_fromFirstSlide_advancesToSecond() {
        assertEquals(1, WalletOnboardingNavigation.nextOnboardingPage(0, sessionSlideCount = 3))
    }

    @Test
    fun nextOnboardingPage_fromLastSlide_transitionsToAuthIndex() {
        assertEquals(3, WalletOnboardingNavigation.nextOnboardingPage(2, sessionSlideCount = 3))
    }

    @Test
    fun nextOnboardingPage_ignoresLivePrefShrink_sessionCountStable() {
        // Regression: pref may flip to skip=true mid-session (live count → 0) while user is on slide 0.
        // Navigation must still advance using the session snapshot (3), not a shrunken live count.
        assertEquals(1, WalletOnboardingNavigation.nextOnboardingPage(0, sessionSlideCount = 3))
    }

    @Test
    fun nextOnboardingPage_whenNoSlides_returnsNull() {
        assertNull(WalletOnboardingNavigation.nextOnboardingPage(0, sessionSlideCount = 0))
    }
}
