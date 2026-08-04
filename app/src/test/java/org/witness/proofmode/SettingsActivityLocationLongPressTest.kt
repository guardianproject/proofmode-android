package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.preference.PreferenceManager
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
class SettingsActivityLocationLongPressTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
        FeatureFlags.resetForTests(context)
        shadowOf(context.applicationContext as Application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Test
    fun longPress_withPermission_togglesPref_noAppInfo() {
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val shadowApp = shadowOf(context.applicationContext as Application)

        activity.findViewById<android.view.View>(R.id.cellLocation).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertTrue(
            shadowApp.nextStartedActivity == null ||
                shadowApp.peekNextStartedActivity()?.action !=
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )

        // Drain any non-App-Info intents
        while (shadowApp.peekNextStartedActivity() != null) {
            shadowApp.nextStartedActivity
        }

        activity.findViewById<android.view.View>(R.id.cellLocation).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, true))
        assertTrue(
            shadowApp.nextStartedActivity == null ||
                shadowApp.peekNextStartedActivity()?.action !=
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )
    }

    @Test
    fun longPress_enableWithoutPermission_setsPending_grantUpsyncs() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<android.view.View>(R.id.cellLocation).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertTrue(activity.pendingEnableLocationForTests())

        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        activity.handleLocationPermissionResultForTests(
            mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to true,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
    }

    @Test
    fun softDeny_clearsPending_noAppInfo() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<android.view.View>(R.id.cellLocation).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()

        val pm = shadowOf(activity.packageManager)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, true)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION, true)

        activity.handleLocationPermissionResultForTests(
            mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to false,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
        assertFalse(activity.pendingEnableLocationForTests())
        val next = shadowOf(context.applicationContext as Application).nextStartedActivity
        assertTrue(next == null || next.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    @Test
    fun permanentDeny_afterRequest_opensAppInfo() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<android.view.View>(R.id.cellLocation).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()

        // Drain permission-request related intents if any
        val shadowApp = shadowOf(context.applicationContext as Application)
        while (shadowApp.peekNextStartedActivity() != null) {
            shadowApp.nextStartedActivity
        }

        val pm = shadowOf(activity.packageManager)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, false)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION, false)

        activity.handleLocationPermissionResultForTests(
            mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to false,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val next = shadowApp.nextStartedActivity
        assertNotNull(next)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, next!!.action)
    }

    @Test
    fun onResume_prefTrueMissingPermissionNoPending_downsyncs_a22() {
        prefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        // setup() already resumed once; pause+resume to re-enter reconcile with pref true
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, true))
        assertFalse(activity.findViewById<android.widget.CheckBox>(R.id.switchLocation).isChecked)
    }

    @Test
    fun tap_stillNavigatesOnly() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val shadowApp = shadowOf(context.applicationContext as Application)
        while (shadowApp.peekNextStartedActivity() != null) {
            shadowApp.nextStartedActivity
        }

        activity.findViewById<android.view.View>(R.id.cellLocation).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val next = shadowApp.nextStartedActivity
        assertNotNull(next)
        assertEquals(LocationSettingsActivity::class.java.name, next!!.component!!.className)
        assertFalse(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
    }
}
