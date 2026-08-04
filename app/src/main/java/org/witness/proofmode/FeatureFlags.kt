package org.witness.proofmode

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.ipfscid.LocalIpfsCidGate
import timber.log.Timber

object FeatureFlags {
    const val PREFS_NAME = "feature_flags"
    const val KEY_LP_ENABLED = "pref_experimental_lp_enabled"
    const val KEY_LP_AUTO_CAPTURE_MODE = "pref_lp_auto_capture_mode"
    const val KEY_LOCAL_IPFS_CID_ENABLED = LocalIpfsCidGate.KEY_ENABLED
    const val KEY_LOCAL_IPFS_CID_USER_SET = "pref_local_ipfs_cid_user_set"
    const val KEY_LP_CID_AUTO_COUPLING_APPLIED = "pref_lp_cid_auto_coupling_applied"

    private lateinit var prefs: SharedPreferences
    private lateinit var defaultPrefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        Timber.d("FeatureFlags initialized. lpEnabled=%s lpActive=%s", lpEnabled, lpActive)
    }

    /** Rebind prefs after clearing storage in unit tests. */
    fun resetForTests(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
    }

    private fun ensureInit() {
        if (!::prefs.isInitialized) {
            throw IllegalStateException("FeatureFlags.init(context) must be called before accessing flags")
        }
    }

    var lpEnabled: Boolean
        get() {
            if (!::prefs.isInitialized) {
                throw IllegalStateException("FeatureFlags.init(context) must be called before accessing flags")
            }
            val stored = prefs.getBoolean(KEY_LP_ENABLED, false)
            Timber.d("FeatureFlags.lpEnabled read: %s", stored)
            return stored
        }
        set(value) {
            if (!::prefs.isInitialized) {
                throw IllegalStateException("FeatureFlags.init(context) must be called before accessing flags")
            }
            Timber.i("FeatureFlags.lpEnabled set to %s", value)
            if (!value) {
                Timber.i("FeatureFlags.lpEnabled disable may require app restart for full teardown")
            }
            prefs.edit().putBoolean(KEY_LP_ENABLED, value).apply()
        }

    var autoCaptureLpMode: AutoCaptureLpMode
        get() {
            if (!::prefs.isInitialized) {
                throw IllegalStateException("FeatureFlags.init(context) must be called before accessing flags")
            }
            return AutoCaptureLpMode.fromPreference(
                prefs.getString(KEY_LP_AUTO_CAPTURE_MODE, AutoCaptureLpMode.PREF_OFF)
            )
        }
        set(value) {
            if (!::prefs.isInitialized) {
                throw IllegalStateException("FeatureFlags.init(context) must be called before accessing flags")
            }
            prefs.edit()
                .putString(KEY_LP_AUTO_CAPTURE_MODE, AutoCaptureLpMode.toPreference(value))
                .apply()
        }

    var localIpfsCidEnabled: Boolean
        get() {
            ensureInit()
            return prefs.getBoolean(KEY_LOCAL_IPFS_CID_ENABLED, false)
        }
        set(value) {
            ensureInit()
            Timber.i("FeatureFlags.localIpfsCidEnabled set to %s", value)
            prefs.edit().putBoolean(KEY_LOCAL_IPFS_CID_ENABLED, value).apply()
        }

    var localIpfsCidUserSet: Boolean
        get() {
            ensureInit()
            return prefs.getBoolean(KEY_LOCAL_IPFS_CID_USER_SET, false)
        }
        set(value) {
            ensureInit()
            prefs.edit().putBoolean(KEY_LOCAL_IPFS_CID_USER_SET, value).apply()
        }

    var lpCidAutoCouplingApplied: Boolean
        get() {
            ensureInit()
            return prefs.getBoolean(KEY_LP_CID_AUTO_COUPLING_APPLIED, false)
        }
        set(value) {
            ensureInit()
            prefs.edit().putBoolean(KEY_LP_CID_AUTO_COUPLING_APPLIED, value).apply()
        }

    /** CR3 step 3: auto-enable CID + latch in one edit; does not set user_set. */
    fun applyLpCidAutoEnable() {
        ensureInit()
        prefs.edit()
            .putBoolean(KEY_LOCAL_IPFS_CID_ENABLED, true)
            .putBoolean(KEY_LP_CID_AUTO_COUPLING_APPLIED, true)
            .apply()
    }

    fun markLpCidAutoCouplingAppliedOnly() {
        ensureInit()
        prefs.edit().putBoolean(KEY_LP_CID_AUTO_COUPLING_APPLIED, true).apply()
    }

    val locationSharingEnabled: Boolean
        get() {
            if (!::defaultPrefs.isInitialized) {
                throw IllegalStateException("FeatureFlags.init(context) must be called before accessing flags")
            }
            return defaultPrefs.getBoolean(
                ProofMode.PREF_OPTION_LOCATION,
                ProofMode.PREF_OPTION_LOCATION_DEFAULT,
            )
        }

    val lpActive: Boolean
        get() = lpEnabled && locationSharingEnabled
}
