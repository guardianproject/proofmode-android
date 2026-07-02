package org.witness.proofmode

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
import org.hamcrest.Matchers.not
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class DeveloperPreviewActivityTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .clear()
            .commit()
        FeatureFlags.init(appContext)
    }

    @Test
    fun toggleLpEnabled_persistsAcrossRecreation() {
        ActivityScenario.launch(DeveloperPreviewActivity::class.java).use { scenario ->
            onView(withText(R.string.developer_preview_lp_title)).perform(click())
            scenario.recreate()

            onView(withId(androidx.preference.R.id.switchWidget)).check(matches(isChecked()))
            assertTrue(FeatureFlags.lpEnabled)
        }
    }

    @Test
    fun developerPreview_visibleOnSettingsLaunch() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.text_developer_preview)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun walletSettings_hiddenWhenLpDisabled() {
        assertTrue(!FeatureFlags.lpEnabled)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.text_wallet_settings)).check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun walletSettings_visibleWhenLpEnabled() {
        FeatureFlags.lpEnabled = true
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.text_wallet_settings)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun locationOff_disablesLpControls() {
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, false)
            .commit()

        ActivityScenario.launch(DeveloperPreviewActivity::class.java).use {
            onView(withText(R.string.developer_preview_lp_auto_capture_summary_location_required))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun locationOn_walletOff_disablesModeListOnly() {
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
            .commit()
        FeatureFlags.lpEnabled = true

        ActivityScenario.launch(DeveloperPreviewActivity::class.java).use {
            onView(withText(R.string.developer_preview_lp_auto_capture_summary_wallet_required))
                .check(matches(isDisplayed()))
        }
    }
}
