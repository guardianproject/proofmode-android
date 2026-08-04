package org.witness.proofmode.plugins.lp.wallet.auth

internal object WalletOnboardingNavigation {

    /**
     * @return next ViewPager position, or null when [current] is not an onboarding slide
     *         or [sessionSlideCount] is zero.
     */
    fun nextOnboardingPage(current: Int, sessionSlideCount: Int): Int? {
        if (sessionSlideCount <= 0 || current >= sessionSlideCount) return null
        return if (current < sessionSlideCount - 1) {
            current + 1
        } else {
            sessionSlideCount // first auth page index
        }
    }
}
