package org.witness.proofmode;

import static org.witness.proofmode.ProofMode.PREFS_DOPROOF;
import static org.witness.proofmode.ProofService.ACTION_START;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import timber.log.Timber;

/**
 * Created by n8fr8 on 10/10/16.
 */
public class ProofModeApp extends MultiDexApplication {


    public final static String TAG = "ProofMode";


    @Override
    public void onCreate() {
        super.onCreate();


            init(this, false);

    }

    public void init (Context context, boolean startService)
    {

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (prefs.getBoolean(PREFS_DOPROOF,false)) {

            Intent intentService = new Intent(context, ProofService.class);
            intentService.setAction(ACTION_START);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ProofMode.initBackgroundService(this);

                //   OneTimeWorkRequest request = new OneTimeWorkRequest.Builder ( BackupWorker.class ).addTag ( "BACKUP_WORKER_TAG" ).build ();
            //    WorkManager.getInstance ( context ).enqueue ( request );

                //not sure what to do here yet!

                if (startService)
                {
                    context.startForegroundService ( intentService );

                }

            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService ( intentService );
            } else {
                context.startService ( intentService );
            }

        }
    }

    public void cancel (Context context)
    {

        Intent intentService = new Intent(context, ProofService.class);
        context.stopService(intentService);

    }

    /** A tree which logs important information for crash reporting. */
    private static class CrashReportingTree extends Timber.Tree {
        @Override protected void log(int priority, String tag, String message, Throwable t) {
            if (priority == Log.VERBOSE || priority == Log.DEBUG) {
                return;
            }
        }
    }

}
