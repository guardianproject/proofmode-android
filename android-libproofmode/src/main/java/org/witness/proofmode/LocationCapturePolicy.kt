package org.witness.proofmode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.preference.PreferenceManager

/**
 * Tier-2 capture-metadata recipe from location-capability-policy:
 * PREF_OPTION_LOCATION && OS location permission (FINE or COARSE).
 * Coarse anchor: MediaWatcher.checkPermissionForLocation(false).
 * Fine anchor: MediaWatcher.checkPermissionForLocation(true) (MediaWatcher.kt:1287-1288).
 */
object LocationCapturePolicy {

    fun hasOsLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Fine-only OS location permission.
     * Pins MediaWatcher.checkPermissionForLocation(true) body
     * (MediaWatcher.kt:1287-1288 / :1284-1297) — ACCESS_FINE_LOCATION only.
     */
    fun hasFineLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun shouldEmbedLocation(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prefOn = prefs.getBoolean(
            ProofMode.PREF_OPTION_LOCATION,
            ProofMode.PREF_OPTION_LOCATION_DEFAULT,
        )
        return prefOn && hasOsLocationPermission(context)
    }
}
