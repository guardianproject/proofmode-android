package org.witness.proofmode;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.StrictMode;
import android.provider.ContactsContract;
import android.util.Log;

import net.hockeyapp.android.CrashManager;
import net.hockeyapp.android.UpdateManager;
import net.hockeyapp.android.metrics.MetricsManager;

import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.witness.proofmode.service.MediaListenerService;
import org.witness.proofmode.service.PhotosContentJob;
import org.witness.proofmode.service.VideosContentJob;
import org.witness.proofmode.util.SafetyNetCheck;

import java.security.Security;
import java.util.HashMap;

import timber.log.Timber;

/**
 * Created by n8fr8 on 10/10/16.
 */
public class ProofModeApp extends Application {


    public final static String TAG = "ProofMode";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public void onCreate() {
        super.onCreate();

        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX);

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        } else {
            Timber.plant(new CrashReportingTree());
        }

        if (android.os.Build.VERSION.SDK_INT >= 24) {
            PhotosContentJob.scheduleJob(this);
            VideosContentJob.scheduleJob(this);
        }
        else
        {
            startService(new Intent(getBaseContext(), MediaListenerService.class));
        }

        SafetyNetCheck.buildGoogleApiClient(this);

        checkForCrashes();
    }

    /** A tree which logs important information for crash reporting. */
    private static class CrashReportingTree extends Timber.Tree {
        @Override protected void log(int priority, String tag, String message, Throwable t) {
            if (priority == Log.VERBOSE || priority == Log.DEBUG) {
                return;
            }

// add this wherever you want to track a custom event
            MetricsManager.trackEvent("Crash: " + tag);

// add this wherever you want to track a custom event and attach properties or measurements to it
            HashMap<String, String> properties = new HashMap<>();
            properties.put("Message", message);

            HashMap<String, Double> measurements = new HashMap<>();
            MetricsManager.trackEvent("YOUR_EVENT_NAME", properties, measurements);

        }
    }

    private void checkForCrashes() {
        CrashManager.register(this);
    }


}
