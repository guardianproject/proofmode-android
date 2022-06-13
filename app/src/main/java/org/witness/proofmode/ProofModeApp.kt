package org.witness.proofmode

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.multidex.MultiDexApplication
import androidx.preference.PreferenceManager
import timber.log.Timber
import timber.log.Timber.Forest.plant

/**
 * Created by n8fr8 on 10/10/16.
 */
class ProofModeApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        init(this)
    }

    fun init(context: Context) {
        if (BuildConfig.DEBUG) {
            plant(Timber.DebugTree())
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(ProofMode.PREFS_DOPROOF, false)) {
            val intentService = Intent(context, ProofService::class.java)
            intentService.action = ProofService.ACTION_START
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intentService)
            } else {
                context.startService(intentService)
            }
        }
    }

    fun cancel(context: Context) {
        val intentService = Intent(context, ProofService::class.java)
        context.stopService(intentService)
    }

    /**
     * A tree which logs important information for crash reporting.
     */
    private class CrashReportingTree : Timber.Tree() {
        protected override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.VERBOSE || priority == Log.DEBUG) {
                return
            }
        }
    }

    companion object {
        const val TAG = "ProofMode"
    }
}