package org.witness.proofmode.plugins.lp.wallet.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletOnboardingPreferencesTest {

    private lateinit var prefs: WalletOnboardingPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("wallet_onboarding_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        prefs = WalletOnboardingPreferences(context)
    }

    @Test
    fun initialSessionSlideCount_whenSkipFalse_returnsThree() {
        assertEquals(3, prefs.initialSessionSlideCount(onboardingTourOnly = false))
    }

    @Test
    fun initialSessionSlideCount_whenSkipTrue_returnsZero() {
        prefs.persistSkipPreference(skip = true)
        assertEquals(0, prefs.initialSessionSlideCount(onboardingTourOnly = false))
    }

    @Test
    fun initialSessionSlideCount_tourOnly_ignoresSkipPref() {
        prefs.persistSkipPreference(skip = true)
        assertEquals(3, prefs.initialSessionSlideCount(onboardingTourOnly = true))
    }

    @Test
    fun clearSkipPreference_resetsSkipFlag() {
        prefs.persistSkipPreference(skip = true)
        prefs.clearSkipPreference()
        assertFalse(prefs.isSkipEnabled())
    }
}
