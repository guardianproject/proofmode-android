package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class LocationSettingsPermissionTest {

    private lateinit var context: Context
    private lateinit var defaultPrefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        defaultPrefs.edit().clear().commit()
        FeatureFlags.resetForTests(context)
        shadowOf(context.applicationContext as Application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Test
    fun configurationChange_preservesPendingEnableIntent_t8() {
        val controller = Robolectric.buildActivity(LocationSettingsActivity::class.java).create()
        val app = LocationSettingsActivityTestSupport.reflectiveProofModeApp(context)
        LocationSettingsActivityTestSupport.bindApplication(controller.get(), app)
        val activity = controller.start().resume().get()
        val fragment = fragment(activity)
        callChangeListener(masterSwitch(fragment), true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertFalse(masterSwitch(fragment).isChecked)

        val bundle = Bundle()
        fragment.onSaveInstanceState(bundle)
        assertTrue(bundle.getBoolean(LocationSettingsActivity.LocationPreferencesFragment.KEY_PENDING_ENABLE_LOCATION))
        assertTrue(bundle.getBoolean(LocationSettingsActivity.LocationPreferencesFragment.KEY_REQUEST_ATTEMPTED))

        val config = Configuration(activity.resources.configuration)
        config.orientation = Configuration.ORIENTATION_LANDSCAPE
        controller.configurationChange(config)
        shadowOf(Looper.getMainLooper()).idle()

        val restored = fragment(controller.get())
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        restored.onResume()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertTrue(masterSwitch(restored).isChecked)
    }

    @Test
    fun processDeath_losesPendingIntent_switchStaysOff_t8() {
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        val fragment = fragment(activity)
        callChangeListener(masterSwitch(fragment), true)
        shadowOf(Looper.getMainLooper()).idle()

        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        val fresh = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        fresh.supportFragmentManager
            .findFragmentById(R.id.location_settings_container)
            .let { it as LocationSettingsActivity.LocationPreferencesFragment }
            .onResume()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertFalse(
            masterSwitch(
                fresh.supportFragmentManager
                    .findFragmentById(R.id.location_settings_container)
                    as LocationSettingsActivity.LocationPreferencesFragment,
            ).isChecked,
        )
    }

    @Test
    fun onResume_withoutPendingIntent_neverAutoEnablesPref_t8() {
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        val fragment = fragment(activity)
        fragment.onResume()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertFalse(masterSwitch(fragment).isChecked)
    }

    @Test
    fun masterEnable_withoutPermission_setsPending_doesNotLaunchPermissionActivity_t8() {
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        val shadowApp = shadowOf(context.applicationContext as Application)
        val fragment = fragment(activity)
        callChangeListener(masterSwitch(fragment), true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        val bundle = Bundle()
        fragment.onSaveInstanceState(bundle)
        assertTrue(bundle.getBoolean(LocationSettingsActivity.LocationPreferencesFragment.KEY_PENDING_ENABLE_LOCATION))

        // D14: no UI-mode PermissionActivity for Location enable
        val next = shadowApp.nextStartedActivity
        if (next != null) {
            assertTrue(
                next.component?.className != PermissionActivity::class.java.name,
            )
        }
    }

    @Test
    fun grantViaCallback_withPending_upsyncsPref_t8() {
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        val fragment = fragment(activity)
        callChangeListener(masterSwitch(fragment), true)
        shadowOf(Looper.getMainLooper()).idle()

        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        fragment.handleLocationPermissionResult(
            mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to true,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertTrue(masterSwitch(fragment).isChecked)
        val bundle = Bundle()
        fragment.onSaveInstanceState(bundle)
        assertFalse(bundle.getBoolean(LocationSettingsActivity.LocationPreferencesFragment.KEY_PENDING_ENABLE_LOCATION))
    }

    @Test
    fun softDeny_clearsPending_noAppInfo_t8() {
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        val fragment = fragment(activity)
        callChangeListener(masterSwitch(fragment), true)
        shadowOf(Looper.getMainLooper()).idle()

        val pm = shadowOf(activity.packageManager)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, true)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION, true)

        fragment.handleLocationPermissionResult(
            mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to false,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        val bundle = Bundle()
        fragment.onSaveInstanceState(bundle)
        assertFalse(bundle.getBoolean(LocationSettingsActivity.LocationPreferencesFragment.KEY_PENDING_ENABLE_LOCATION))

        val next = shadowOf(context.applicationContext as Application).nextStartedActivity
        assertTrue(
            next == null ||
                next.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )
    }

    @Test
    fun permanentDeny_afterRequest_opensAppInfo_t8() {
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        val fragment = fragment(activity)
        callChangeListener(masterSwitch(fragment), true)
        shadowOf(Looper.getMainLooper()).idle()

        val pm = shadowOf(activity.packageManager)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, false)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION, false)

        fragment.handleLocationPermissionResult(
            mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to false,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val next = shadowOf(context.applicationContext as Application).nextStartedActivity
        assertNotNull(next)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, next!!.action)
    }

    @Test
    fun onResume_prefTrueMissingPermissionNoPending_downsyncsFalse_f26() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        val fragment = fragment(activity)
        fragment.onResume()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(defaultPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, true))
        assertFalse(masterSwitch(fragment).isChecked)
    }

    private fun fragment(activity: LocationSettingsActivity): LocationSettingsActivity.LocationPreferencesFragment =
        activity.supportFragmentManager
            .findFragmentById(R.id.location_settings_container)
            as LocationSettingsActivity.LocationPreferencesFragment

    private fun masterSwitch(
        fragment: LocationSettingsActivity.LocationPreferencesFragment,
    ): SwitchPreferenceCompat = fragment.findPreference("locationSharingMaster")!!

    private fun callChangeListener(preference: Preference, newValue: Any) {
        val method = Preference::class.java.getDeclaredMethod("callChangeListener", Any::class.java)
        method.isAccessible = true
        method.invoke(preference, newValue)
    }
}
