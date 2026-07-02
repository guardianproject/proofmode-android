package org.witness.proofmode.plugins.ipfscid

import android.content.Context

object LocalIpfsCidGate {
    const val PREFS_NAME = "feature_flags"
    const val KEY_ENABLED = "pref_experimental_local_ipfs_cid_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
}
