package org.witness.proofmode;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.witness.proofmode.library.R;
import org.witness.proofmode.service.AudioContentJob;
import org.witness.proofmode.service.CameraEventReceiver;
import org.witness.proofmode.service.MediaWatcher;
import org.witness.proofmode.service.PhotosContentJob;
import org.witness.proofmode.service.VideosContentJob;
import org.witness.proofmode.util.SafetyNetCheck;

import java.io.File;
import java.security.Security;


public class ProofMode {

    public final static String PREF_OPTION_NOTARY = "autoNotarize";
    public final static String PREF_OPTION_LOCATION = "trackLocation";
    public final static String PREF_OPTION_PHONE = "trackDeviceId";
    public final static String PREF_OPTION_NETWORK = "trackMobileNetwork";

    public final static boolean PREF_OPTION_NOTARY_DEFAULT = true;
    public final static boolean PREF_OPTION_LOCATION_DEFAULT = false;
    public final static boolean PREF_OPTION_PHONE_DEFAULT = true;
    public final static boolean PREF_OPTION_NETWORK_DEFAULT = true;


    public final static String PROOF_FILE_TAG = ".proof.csv";
    public final static String PROOF_FILE_JSON_TAG = ".proof.json";
    public final static String OPENPGP_FILE_TAG = ".asc";
    public final static String OPENTIMESTAMPS_FILE_TAG = ".ots";
    public final static String GOOGLE_SAFETYNET_FILE_TAG = ".gst";
    public final static String PROVIDER_TAG = ".provider";

    public final static String PREFS_DOPROOF = "doProof";

    public final static BouncyCastleProvider sProvider = new BouncyCastleProvider();
    static {
        Security.addProvider(sProvider);
    }

    private static CameraEventReceiver mReceiver;

    private static boolean mInit = false;

    public synchronized static void init (Context context)
    {
        if (mInit)
            return;

        if (Build.VERSION.SDK_INT >= 24) {
            PhotosContentJob.scheduleJob(context);
            VideosContentJob.scheduleJob(context);
            AudioContentJob.scheduleJob(context);
        }
        else
        {
            mReceiver = new CameraEventReceiver();
            context.registerReceiver(mReceiver, new IntentFilter("com.android.camera.NEW_PICTURE"));
            context.registerReceiver(mReceiver, new IntentFilter("android.hardware.action.NEW_PICTURE"));
            context.registerReceiver(mReceiver, new IntentFilter("com.android.camera.NEW_VIDEO"));
        }

        mInit = true;

        MediaWatcher.getInstance(context);


        SafetyNetCheck.setApiKey(context.getString(R.string.verification_api_key));

    }

    public static void stop (Context context)
    {
        if (Build.VERSION.SDK_INT >= 24) {
            PhotosContentJob.cancelJob(context);
            VideosContentJob.cancelJob(context);
            AudioContentJob.cancelJob(context);
        }
        else
        {
            if (mReceiver != null)
                context.unregisterReceiver(mReceiver);
        }

        MediaWatcher.getInstance(context).stop();

    }

    public static BouncyCastleProvider getProvider ()
    {
        return sProvider;
    }

    public static String generateProof (Context context, Uri uri)
    {

        return MediaWatcher.getInstance(context).processUri (uri, false);

    }


    public static String generateProof (Context context, Uri uri, String proofHash)
    {

        return MediaWatcher.getInstance(context).processUri (uri, proofHash, false);

    }


    public static File getProofDir (Context context, String mediaHash)
    {
        return MediaWatcher.getHashStorageDir(context, mediaHash);
    }


}
