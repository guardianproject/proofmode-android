package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class LocationSharingPermissionSyncTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
        FeatureFlags.resetForTests(context)
        shadowOf(context.applicationContext as Application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Test
    fun reconcile_pendingAndGranted_writesTrueAndClearsPending() {
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        prefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()

        val snap = LocationSharingPermissionSync.reconcileOnResume(context, pendingEnable = true)

        assertTrue(snap.locationSharingEnabled)
        assertFalse(snap.pendingEnable)
        assertTrue(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
    }

    @Test
    fun reconcile_prefTrueMissingPermissionNoPending_writesFalse() {
        prefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()

        val snap = LocationSharingPermissionSync.reconcileOnResume(context, pendingEnable = false)

        assertFalse(snap.locationSharingEnabled)
        assertFalse(snap.pendingEnable)
        assertFalse(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, true))
    }

    @Test
    fun reconcile_prefTrueMissingPermissionButPending_doesNotDownsync() {
        prefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()

        val snap = LocationSharingPermissionSync.reconcileOnResume(context, pendingEnable = true)

        assertTrue(snap.locationSharingEnabled)
        assertTrue(snap.pendingEnable)
        assertTrue(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
    }

    @Test
    fun beginDisable_writesFalse_clearsPendingSemantics() {
        prefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        val snap = LocationSharingPermissionSync.beginDisable(context)

        assertFalse(snap.locationSharingEnabled)
        assertFalse(snap.pendingEnable)
        assertFalse(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, true))
        // Disable must not revoke OS grant
        assertTrue(LocationSharingPermissionSync.hasLocationPermission(context))
    }

    @Test
    fun beginEnable_whenGranted_alreadyGrantedTrue() {
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val plan = LocationSharingPermissionSync.beginEnable(context)

        assertTrue(plan.alreadyGranted)
        assertFalse(plan.needsPermissionRequest)
        assertTrue(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
    }

    @Test
    fun beginEnable_whenMissing_needsRequest_doesNotOpenAppInfoPreCheck() {
        val plan = LocationSharingPermissionSync.beginEnable(context)

        assertFalse(plan.alreadyGranted)
        assertTrue(plan.needsPermissionRequest)
        assertFalse(
            prefs.getBoolean(
                ProofMode.PREF_OPTION_LOCATION,
                ProofMode.PREF_OPTION_LOCATION_DEFAULT,
            ),
        )
    }

    @Test
    fun onResult_granted_withPending_upsyncsAndClears() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        val result = LocationSharingPermissionSync.onPermissionLauncherResult(
            activity = activity,
            context = context,
            pendingEnable = true,
            requestAttempted = true,
            grantMap = mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to true,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )

        assertTrue(result.locationSharingEnabled)
        assertFalse(result.pendingEnable)
        assertFalse(result.openAppInfo)
        assertTrue(prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, false))
    }

    @Test
    fun onResult_deniedWithRationale_clearsPending_noAppInfo() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val pm = shadowOf(activity.packageManager)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, true)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION, true)

        val result = LocationSharingPermissionSync.onPermissionLauncherResult(
            activity = activity,
            context = context,
            pendingEnable = true,
            requestAttempted = true,
            grantMap = mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to false,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )

        assertFalse(result.locationSharingEnabled)
        assertFalse(result.pendingEnable)
        assertFalse(result.openAppInfo)
    }

    @Test
    fun onResult_deniedWithoutRationale_afterRequest_opensAppInfo() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val pm = shadowOf(activity.packageManager)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, false)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION, false)

        val result = LocationSharingPermissionSync.onPermissionLauncherResult(
            activity = activity,
            context = context,
            pendingEnable = true,
            requestAttempted = true,
            grantMap = mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to false,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )

        assertFalse(result.pendingEnable)
        assertTrue(result.openAppInfo)
    }

    @Test
    fun onResult_deniedWithoutRationale_butRequestNotAttempted_noAppInfo() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val pm = shadowOf(activity.packageManager)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, false)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION, false)

        val result = LocationSharingPermissionSync.onPermissionLauncherResult(
            activity = activity,
            context = context,
            pendingEnable = true,
            requestAttempted = false,
            grantMap = mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to false,
                Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
        )

        assertFalse(result.pendingEnable)
        assertFalse(result.openAppInfo)
    }

    @Test
    fun hasLocationPermission_fineOrCoarse() {
        assertFalse(LocationSharingPermissionSync.hasLocationPermission(context))

        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        assertTrue(LocationSharingPermissionSync.hasLocationPermission(context))

        shadowOf(context.applicationContext as Application).denyPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        assertTrue(LocationSharingPermissionSync.hasLocationPermission(context))
    }

    @Test
    fun applicationDetailsIntent_targetsPackage() {
        val intent = LocationSharingPermissionSync.applicationDetailsIntent("org.witness.proofmode")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package", intent.data?.scheme)
        assertEquals("org.witness.proofmode", intent.data?.schemeSpecificPart)
    }
}
