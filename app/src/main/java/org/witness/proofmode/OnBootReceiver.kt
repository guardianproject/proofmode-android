package org.witness.proofmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager

/**
 * Created by n8fr8 on 10/10/16.
 */
class OnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(ProofMode.PREFS_DOPROOF, false)) {

            ProofMode.initBackgroundService(context)

            /**
            val intentService = Intent(context, ProofService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intentService)
            } else {
                context.startService(intentService)
            }**/
        }
    }
}