package org.witness.proofmode;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.service.MediaListenerService;
import org.witness.proofmode.service.MediaWatcher;
import org.witness.proofmode.service.PhotosContentJob;
import org.witness.proofmode.service.VideosContentJob;

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
    public final static String OPENPGP_FILE_TAG = ".asc";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static boolean mInit = false;

    public synchronized static void init (Context context)
    {
        if (mInit)
            return;

        if (android.os.Build.VERSION.SDK_INT >= 24) {
            PhotosContentJob.scheduleJob(context);
            VideosContentJob.scheduleJob(context);
        }
        else
        {
            context.startService(new Intent(context, MediaListenerService.class));
        }

        mInit = true;
    }

    public static String generateProof (Context context, Uri uri)
    {
        Intent intent = new Intent();
        intent.setData(uri);
        return new MediaWatcher().handleIntent(context, intent, true);
    }


    public static File getProofDir (String mediaHash)
    {
        return MediaWatcher.getHashStorageDir(mediaHash);
    }


}
