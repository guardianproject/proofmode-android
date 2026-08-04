package org.witness.proofmode

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class FeatureFlagsLpActiveTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        FeatureFlags.resetForTests(context)
    }

    @Test
    fun lpActive_falseWhenMasterOffEvenIfLpEnabled() {
        FeatureFlags.resetForTests(context)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).apply()
        FeatureFlags.lpEnabled = true
        assertFalse(FeatureFlags.lpActive)
    }

    @Test
    fun lpActive_trueWhenMasterAndLpOn() {
        FeatureFlags.resetForTests(context)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).apply()
        FeatureFlags.lpEnabled = true
        assertTrue(FeatureFlags.lpActive)
    }

    @Test
    fun resetForTests_rebindsDefaultPrefs() {
        FeatureFlags.resetForTests(context)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
        assertTrue(FeatureFlags.locationSharingEnabled)

        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        FeatureFlags.resetForTests(context)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()
        assertFalse(FeatureFlags.locationSharingEnabled)
    }
}
