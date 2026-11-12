package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.witness.proofmode.LocationSettingsActivityTestSupport.fragment
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class LocationSettingsStoreRoutingTest {

    private lateinit var context: Context
    private lateinit var featureFlags: SharedPreferences
    private lateinit var defaultPrefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        featureFlags = context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        featureFlags.edit().clear().commit()
        defaultPrefs.edit().clear().commit()
        FeatureFlags.resetForTests(context)
    }

    @After
    fun tearDown() {
        LocationSettingsActivityTestSupport.tearDown()
    }

    private fun launchActivity(): LocationSettingsActivity =
        LocationSettingsActivityTestSupport.launchLocationSettingsActivity()

    private fun fragment(): LocationSettingsActivity.LocationPreferencesFragment = fragment(launchActivity())

    @Test
    fun onOpen_defaultPrefsMayContainTrackLocation() {
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        defaultPrefs.edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
            .apply()
        launchActivity()
        assertTrue(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertFalse(featureFlags.contains(ProofMode.PREF_OPTION_LOCATION))
    }

    @Test
    fun onToggleMaster_writesTrackLocationToDefaultPrefsOnly() {
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val fragment = fragment()
        val master = fragment.findPreference<SwitchPreferenceCompat>("locationSharingMaster")!!

        master.callChangeListener(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertFalse(featureFlags.contains(ProofMode.PREF_OPTION_LOCATION))
    }

    private class StoreCase(val label: String, val key: String, val newValue: Any)

    @Test
    fun onToggleLpAutoCaptureCid_writeToFeatureFlagsOnly() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).apply()
        val fragment = fragment()

        // Order matters: the cid toggle sets userSet, which blocks the lp toggle's auto-enable of cid.
        // With lp first, cid would already be true and its assertion would pass vacuously.
        val cases = listOf(
            StoreCase("cid", FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED, true),
            StoreCase("auto-capture mode", FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE, AutoCaptureLpMode.PREF_ONCHAIN),
            StoreCase("lp", FeatureFlags.KEY_LP_ENABLED, true),
        )
        for (case in cases) {
            val preference = fragment.findPreference<Preference>(case.key)!!
            // The cascade re-disables rows after each toggle while lp is off, and disabled rows reject the write.
            preference.isEnabled = true

            preference.callChangeListener(case.newValue)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(case.label, case.newValue, featureFlags.all[case.key])
            assertFalse(case.label, defaultPrefs.contains(case.key))
        }
    }
}
