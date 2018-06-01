package org.witness.proofmode;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.support.multidex.MultiDexApplication;
import android.util.Log;
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
public class ProofModeApp extends MultiDexApplication {


    public final static String TAG = "ProofMode";


    @Override
    public void onCreate() {
        super.onCreate();

        init(this);
    }

    public static void init (Context context)
    {

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        } else {
            Timber.plant(new CrashReportingTree());
        }

        ProofMode.init(context);
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
