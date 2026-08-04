package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationCapturePolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        shadowOf(context.applicationContext as Application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Test
    fun shouldEmbedLocation_falseWhenPrefOffEvenIfPermissionGranted() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, false)
            .commit()
        assertFalse(LocationCapturePolicy.shouldEmbedLocation(context))
    }

    @Test
    fun shouldEmbedLocation_falseWhenPrefOnButPermissionDenied() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
            .commit()
        assertFalse(LocationCapturePolicy.shouldEmbedLocation(context))
    }

    @Test
    fun shouldEmbedLocation_trueWhenPrefOnAndFineGranted() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
            .commit()
        assertTrue(LocationCapturePolicy.shouldEmbedLocation(context))
    }

    @Test
    fun shouldEmbedLocation_trueWhenPrefOnAndOnlyCoarseGranted() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
            .commit()
        assertTrue(LocationCapturePolicy.shouldEmbedLocation(context))
    }

    @Test
    fun hasFineLocationPermission_falseWhenOnlyCoarseGranted() {
        shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(LocationCapturePolicy.hasFineLocationPermission(context))
    }

    @Test
    fun hasFineLocationPermission_trueWhenFineGranted() {
        shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(LocationCapturePolicy.hasFineLocationPermission(context))
    }

    @Test
    fun hasFineLocationPermission_falseWhenDenied() {
        assertFalse(LocationCapturePolicy.hasFineLocationPermission(context))
    }

    @Test
    fun hasOsLocationPermission_trueWhenOnlyCoarseGranted() {
        shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(LocationCapturePolicy.hasOsLocationPermission(context))
    }
}
