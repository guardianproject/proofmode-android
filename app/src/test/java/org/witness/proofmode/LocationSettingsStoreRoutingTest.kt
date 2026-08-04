package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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

    private fun launchActivity(): LocationSettingsActivity =
        LocationSettingsActivityTestSupport.launchLocationSettingsActivity()

    private fun fragment(): LocationSettingsActivity.LocationPreferencesFragment {
        val activity = launchActivity()
        return activity.supportFragmentManager
            .findFragmentById(R.id.location_settings_container)
            as LocationSettingsActivity.LocationPreferencesFragment
    }

    private fun callChangeListener(preference: Preference, newValue: Any) {
        val method = Preference::class.java.getDeclaredMethod("callChangeListener", Any::class.java)
        method.isAccessible = true
        method.invoke(preference, newValue)
    }

    @Test
    fun onOpen_locationPermissionPreferenceRowRemoved_cr2() {
        val fragment = fragment()
        org.junit.Assert.assertNull(fragment.findPreference<Preference>("locationPermission"))
    }

    @Test
    fun onOpen_featureFlagsNeverContainsTrackLocation() {
        defaultPrefs.edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
            .apply()
        launchActivity()
        assertFalse(featureFlags.contains(ProofMode.PREF_OPTION_LOCATION))
    }

    @Test
    fun onOpen_migratedKeysLiveInFeatureFlagsNotDefaultPrefs() {
        launchActivity()
        assertTrue(featureFlags.contains(FeatureFlags.KEY_LP_ENABLED))
        assertTrue(featureFlags.contains(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE))
        assertTrue(featureFlags.contains(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED))
        assertFalse(defaultPrefs.contains(FeatureFlags.KEY_LP_ENABLED))
        assertFalse(defaultPrefs.contains(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE))
        assertFalse(defaultPrefs.contains(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED))
    }

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

        callChangeListener(master, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertFalse(featureFlags.contains(ProofMode.PREF_OPTION_LOCATION))
    }

    @Test
    fun onToggleLp_writesToFeatureFlagsOnly() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).apply()
        val fragment = fragment()
        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!

        callChangeListener(lp, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(featureFlags.getBoolean(FeatureFlags.KEY_LP_ENABLED, false))
        assertFalse(defaultPrefs.contains(FeatureFlags.KEY_LP_ENABLED))
        assertFalse(featureFlags.contains(ProofMode.PREF_OPTION_LOCATION))
    }

    @Test
    fun onToggleAutoCapture_writesToFeatureFlagsOnly() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).apply()
        FeatureFlags.lpEnabled = true
        val fragment = fragment()
        val mode = fragment.findPreference<ListPreference>(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE)!!
        mode.isEnabled = true

        callChangeListener(mode, AutoCaptureLpMode.PREF_ONCHAIN)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            AutoCaptureLpMode.PREF_ONCHAIN,
            featureFlags.getString(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE, null),
        )
        assertFalse(defaultPrefs.contains(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE))
        assertFalse(featureFlags.contains(ProofMode.PREF_OPTION_LOCATION))
    }

    @Test
    fun onToggleCid_writesToFeatureFlagsOnly() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).apply()
        FeatureFlags.lpEnabled = true
        val fragment = fragment()
        val cid = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)!!
        cid.isEnabled = true

        callChangeListener(cid, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(featureFlags.getBoolean(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED, false))
        assertFalse(defaultPrefs.contains(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED))
        assertFalse(featureFlags.contains(ProofMode.PREF_OPTION_LOCATION))
    }
}
