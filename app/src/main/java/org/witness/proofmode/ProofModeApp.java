package org.witness.proofmode;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.ContactsContract;

import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.witness.proofmode.service.MediaListenerService;
import org.witness.proofmode.service.PhotosContentJob;
import org.witness.proofmode.service.VideosContentJob;
import org.witness.proofmode.util.SafetyNetCheck;

import java.security.Security;

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

        if (android.os.Build.VERSION.SDK_INT >= 24) {
            PhotosContentJob.scheduleJob(this);
            VideosContentJob.scheduleJob(this);
        }
        else
        {
            startService(new Intent(getBaseContext(), MediaListenerService.class));
        }

        SafetyNetCheck.buildGoogleApiClient(this);
    }

}
