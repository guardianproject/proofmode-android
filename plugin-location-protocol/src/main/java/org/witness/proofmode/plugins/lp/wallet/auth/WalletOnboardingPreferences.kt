package org.witness.proofmode.plugins.lp.wallet.auth

import android.content.Context

internal class WalletOnboardingPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSkipEnabled(): Boolean = prefs.getBoolean(PREF_SKIP_ONBOARDING, false)

    fun initialSessionSlideCount(onboardingTourOnly: Boolean): Int = when {
        onboardingTourOnly -> ONBOARDING_SLIDE_COUNT
        isSkipEnabled() -> 0
        else -> ONBOARDING_SLIDE_COUNT
    }

    fun persistSkipPreference(skip: Boolean) {
        prefs.edit().putBoolean(PREF_SKIP_ONBOARDING, skip).apply()
    }

    fun clearSkipPreference() = persistSkipPreference(skip = false)

    companion object {
        const val PREFS_NAME = "wallet_onboarding_prefs"
        const val PREF_SKIP_ONBOARDING = "pref_skip_wallet_onboarding"
        const val ONBOARDING_SLIDE_COUNT = 3
    }
}
