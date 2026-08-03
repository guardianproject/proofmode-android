package org.witness.proofmode

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager

object LocationSharingPermissionSync {

    val LOCATION_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    data class ReconcileSnapshot(
        val locationSharingEnabled: Boolean,
        val pendingEnable: Boolean,
    )

    data class EnablePlan(
        val alreadyGranted: Boolean,
        val needsPermissionRequest: Boolean,
    )

    data class PermissionCallbackResult(
        val locationSharingEnabled: Boolean,
        val pendingEnable: Boolean,
        val openAppInfo: Boolean,
    )

    fun hasLocationPermission(context: Context): Boolean =
        PermissionActivity.hasPermissions(context, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) ||
            PermissionActivity.hasPermissions(context, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))

    fun reconcileOnResume(context: Context, pendingEnable: Boolean): ReconcileSnapshot {
        val prefs = defaultPrefs(context)
        val granted = hasLocationPermission(context)
        var pending = pendingEnable
        if (pending && granted) {
            prefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).apply()
            pending = false
        } else if (
            prefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, ProofMode.PREF_OPTION_LOCATION_DEFAULT) &&
            !granted &&
            !pending
        ) {
            prefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).apply()
        }
        val enabled = prefs.getBoolean(
            ProofMode.PREF_OPTION_LOCATION,
            ProofMode.PREF_OPTION_LOCATION_DEFAULT,
        )
        return ReconcileSnapshot(locationSharingEnabled = enabled, pendingEnable = pending)
    }

    fun beginDisable(context: Context): ReconcileSnapshot {
        defaultPrefs(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, false)
            .apply()
        return ReconcileSnapshot(locationSharingEnabled = false, pendingEnable = false)
    }

    fun beginEnable(context: Context): EnablePlan {
        return if (hasLocationPermission(context)) {
            defaultPrefs(context).edit()
                .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
                .apply()
            EnablePlan(alreadyGranted = true, needsPermissionRequest = false)
        } else {
            // NEVER pre-check shouldShowRequestPermissionRationale here (F9 / D14).
            EnablePlan(alreadyGranted = false, needsPermissionRequest = true)
        }
    }

    fun onPermissionLauncherResult(
        activity: Activity,
        context: Context,
        pendingEnable: Boolean,
        requestAttempted: Boolean,
        grantMap: Map<String, Boolean>,
    ): PermissionCallbackResult {
        val granted = grantMap[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grantMap[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            hasLocationPermission(context)

        if (granted) {
            if (pendingEnable) {
                defaultPrefs(context).edit()
                    .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
                    .apply()
            }
            return PermissionCallbackResult(
                locationSharingEnabled = defaultPrefs(context).getBoolean(
                    ProofMode.PREF_OPTION_LOCATION,
                    ProofMode.PREF_OPTION_LOCATION_DEFAULT,
                ),
                pendingEnable = false,
                openAppInfo = false,
            )
        }

        // Non-grant: always clear pending (F26 / A22).
        // Match PermissionActivity: permanent denial when any still-missing permission lacks rationale.
        val permanentlyDenied = requestAttempted &&
            LOCATION_PERMISSIONS.any { permission ->
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            } &&
            !hasLocationPermission(context)

        return PermissionCallbackResult(
            locationSharingEnabled = defaultPrefs(context).getBoolean(
                ProofMode.PREF_OPTION_LOCATION,
                ProofMode.PREF_OPTION_LOCATION_DEFAULT,
            ),
            pendingEnable = false,
            openAppInfo = permanentlyDenied,
        )
    }

    fun applicationDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }

    private fun defaultPrefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)
}
