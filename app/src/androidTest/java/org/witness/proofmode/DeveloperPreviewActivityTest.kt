package org.witness.proofmode

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.hamcrest.Matchers.not
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
        FeatureFlags.resetForTests(appContext)
    }

    @Test
    fun developerPreview_hiddenOnSettingsLaunch() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.text_developer_preview)).check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun developerPreview_showsEmptyState() {
        ActivityScenario.launch(DeveloperPreviewActivity::class.java).use {
            onView(withText(R.string.developer_preview_empty_title)).check(matches(isDisplayed()))
            onView(withText(R.string.developer_preview_empty_summary)).check(matches(isDisplayed()))
        }
    }
}
