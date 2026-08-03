package org.witness.proofmode

import android.app.Instrumentation
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class LocationSettingsActivityTest {

    private lateinit var appContext: Context
    private lateinit var defaultPrefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        defaultPrefs.edit().clear().commit()
        FeatureFlags.resetForTests(appContext)
    }

    @Test
    fun cellLocationTap_launchesLocationSettings_doesNotMutateTrackLocation_t6() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()
        val monitor = activityMonitorFor(LocationSettingsActivity::class.java)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.cellLocation)).perform(click())
        }
        val launched = monitor.waitForActivityWithTimeout(5_000)
        assertNotNull(launched)
        assertFalse(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, true))
        launched?.finish()
    }

    @Test
    fun switchLocationClick_launchesLocationSettings_doesNotFlipPref_t7() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()
        val monitor = activityMonitorFor(LocationSettingsActivity::class.java)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.switchLocation)).perform(click())
        }
        val launched = monitor.waitForActivityWithTimeout(5_000)
        assertNotNull(launched)
        assertFalse(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, true))
        launched?.finish()
    }

    @Test
    fun locationOff_disablesLpControls() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()
        ActivityScenario.launch(LocationSettingsActivity::class.java).use {
            onView(withText(R.string.location_protocol_enable_summary_location_required))
                .check(matches(isDisplayed()))
            onView(withText(R.string.location_protocol_auto_capture_summary_location_required))
                .check(matches(isDisplayed()))
            onView(withText(R.string.compute_cids_enable_summary_location_required))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun locationOn_walletOff_disablesModeListOnly() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
        FeatureFlags.lpEnabled = true
        ActivityScenario.launch(LocationSettingsActivity::class.java).use {
            onView(withText(R.string.location_protocol_auto_capture_summary_wallet_required))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun walletRow_masterOff_disconnected_grayedWithLocationRequired_f13() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()
        ActivityScenario.launch(LocationSettingsActivity::class.java).use {
            onView(withText(R.string.location_wallet_summary_location_required))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun walletRow_lpOff_disconnected_showsLpRequiredSummary_f13() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
        assertFalse(FeatureFlags.lpEnabled)
        ActivityScenario.launch(LocationSettingsActivity::class.java).use {
            onView(withText(R.string.location_wallet_summary_lp_required))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun toggleLpEnabled_persistsAcrossRecreation() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
        ActivityScenario.launch(LocationSettingsActivity::class.java).use { scenario ->
            onView(withText(R.string.location_protocol_enable_title)).perform(click())
            onView(withId(androidx.preference.R.id.switchWidget)).perform(click())
            scenario.recreate()
            onView(withId(androidx.preference.R.id.switchWidget)).check(matches(isChecked()))
            assertTrue(FeatureFlags.lpEnabled)
        }
    }

    private fun activityMonitorFor(activityClass: Class<*>): Instrumentation.ActivityMonitor =
        InstrumentationRegistry.getInstrumentation()
            .addMonitor(activityClass.name, null, false)
}
