package org.witness.proofmode;

import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPUtil;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.pgp.PgpUtils;
import org.witness.proofmode.notarization.NotarizationProvider;
import org.witness.proofmode.service.AudioContentJob;
import org.witness.proofmode.service.CameraEventReceiver;
import org.witness.proofmode.service.MediaWatcher;
import org.witness.proofmode.service.PhotosContentJob;
import org.witness.proofmode.service.VideosContentJob;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.security.Security;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import timber.log.Timber;


public class ProofMode {

    public final static String PREF_OPTION_NOTARY = "autoNotarize";
    public final static String PREF_OPTION_LOCATION = "trackLocation";
    public final static String PREF_OPTION_PHONE = "trackDeviceId";
    public final static String PREF_OPTION_NETWORK = "trackMobileNetwork";

    public final static boolean PREF_OPTION_NOTARY_DEFAULT = true;
    public final static boolean PREF_OPTION_LOCATION_DEFAULT = false;
    public final static boolean PREF_OPTION_PHONE_DEFAULT = false;
    public final static boolean PREF_OPTION_NETWORK_DEFAULT = true;


    public final static String PROOF_FILE_TAG = ".proof.csv";
    public final static String PROOF_FILE_JSON_TAG = ".proof.json";
    public final static String OPENPGP_FILE_TAG = ".asc";
    public final static String OPENTIMESTAMPS_FILE_TAG = ".ots";
    public final static String GOOGLE_SAFETYNET_FILE_TAG = ".gst";
    public final static String PROVIDER_TAG = ".provider";

    public final static String PUBKEY_FILE = "pubkey.asc";

    public final static String PREFS_DOPROOF = "doProof";

    public final static BouncyCastleProvider sProvider = new BouncyCastleProvider();
    static {
        Security.addProvider(sProvider);
    }

    private static CameraEventReceiver mReceiver;

    private static boolean mInit = false;

    public synchronized static void initBackgroundService (Context context)
    {
        if (mInit)
            return;

        if (Build.VERSION.SDK_INT >= 24) {
            PhotosContentJob.scheduleJob(context);
            VideosContentJob.scheduleJob(context);
            AudioContentJob.scheduleJob(context);
        }

        mReceiver = new CameraEventReceiver();
        addCameraEventListeners(context, mReceiver);

        mInit = true;

        MediaWatcher.getInstance(context);



    }

    private static void addCameraEventListeners (Context context, CameraEventReceiver receiver) {

        //external potential camera events
        context.registerReceiver(receiver, new IntentFilter("com.android.camera.NEW_PICTURE"));
        context.registerReceiver(receiver, new IntentFilter("android.hardware.action.NEW_PICTURE"));
        context.registerReceiver(receiver, new IntentFilter("com.android.camera.NEW_VIDEO"));
        context.registerReceiver(receiver, new IntentFilter("org.witness.proofmode.NEW_MEDIA"));

        //internal camera event
        LocalBroadcastManager.getInstance(context).
                registerReceiver(receiver, new IntentFilter("org.witness.proofmode.NEW_MEDIA"));


    }

    public static void stopBackgroundService (Context context)
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

            LocalBroadcastManager.getInstance(context).unregisterReceiver(mReceiver);
        }

        MediaWatcher.getInstance(context).stop();

    }

    public static BouncyCastleProvider getProvider ()
    {
        return sProvider;
    }

    public static String generateProof (Context context, Uri uri)
    {

        return MediaWatcher.getInstance(context).processUri (uri, false, null);

    }

    public static String generateProof (Context context, Uri uri, byte[] mediaBytes, String mimeType)
    {

        return MediaWatcher.getInstance(context).processBytes (context, uri, mediaBytes, mimeType, null);

    }

    public static String generateProof (Context context, Uri uri, byte[] mediaBytes, String mimeType, Date createdAt)
    {

        return MediaWatcher.getInstance(context).processBytes (context, uri, mediaBytes, mimeType, createdAt);

    }

    public static String generateProof (Context context, Uri uri, String proofHash)
    {

        return MediaWatcher.getInstance(context).processUri (uri, proofHash, false, null);

    }

    public static File getProofDir (Context context, String mediaHash)
    {
        return MediaWatcher.getHashStorageDir(context, mediaHash);
    }

    public static void setProofPoints (Context context, boolean deviceIds, boolean location, boolean networks, boolean notarization)
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        SharedPreferences.Editor editor = prefs.edit();

        editor.putBoolean(ProofMode.PREF_OPTION_PHONE, deviceIds);
        editor.putBoolean(ProofMode.PREF_OPTION_LOCATION, location);
        editor.putBoolean(ProofMode.PREF_OPTION_NOTARY, notarization);
        editor.putBoolean(ProofMode.PREF_OPTION_NETWORK, networks);

        editor.apply();

    }


    public static void addNotarizationProvider (Context context, NotarizationProvider provider) {
        MediaWatcher.getInstance(context).addNotarizationProvider(provider);
    }

    public static PGPPublicKey getPublicKey (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PgpUtils pu = PgpUtils.getInstance(context,prefs.getString("password",PgpUtils.DEFAULT_PASSWORD));
        PGPPublicKey pubKey = null;
        return pubKey = pu.getPublicKey();

    }

    public static String getPublicKeyString (Context context) throws IOException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PgpUtils pu = PgpUtils.getInstance(context,prefs.getString("password",PgpUtils.DEFAULT_PASSWORD));
        String pubKey = pu.getPublicKeyString();

        return pubKey;
    }

    public static boolean verifyProofZip (Context context, String mediaHashSha256, InputStream mediaFile, InputStream proofZipStream) throws Exception {

        boolean verifiedIntegrity = verifyProofZipIntegrity(context, mediaHashSha256, mediaFile, proofZipStream);


        return verifiedIntegrity;
    }

    public static boolean verifyProofZipIntegrity (Context context, String mediaHashSha256, InputStream mediaFile, InputStream proofZipStream) throws Exception {

        InputStream mediaSig = null;
        InputStream proofFile = null;
        InputStream proofFileSig = null;
        InputStream pubKey = null;

        try (ZipInputStream zis = new ZipInputStream(proofZipStream)) {

            // list files in zip
            ZipEntry zipEntry = zis.getNextEntry();

            while (zipEntry != null) {


               if (!zipEntry.getName().endsWith(File.separator)) {

                   if (zipEntry.getName().equals(mediaHashSha256 + OPENPGP_FILE_TAG))
                   {
                       mediaSig = copyStream(zis);
                   }
                   else if (zipEntry.getName().equals(mediaHashSha256 + PROOF_FILE_TAG)) {
                       //the proof file
                       proofFile = copyStream(zis);
                   }
                   else if (zipEntry.getName().equals(mediaHashSha256 + PROOF_FILE_TAG + OPENPGP_FILE_TAG)) {
                       //the proof file
                       proofFileSig = copyStream(zis);
                   }
                   else if (zipEntry.getName().equals(PUBKEY_FILE)) {
                       //the proof file
                       pubKey = copyStream(zis);
                   }
               }



                zipEntry = zis.getNextEntry();

            }
            zis.closeEntry();

        }

        if (mediaSig == null)
            throw new ProofException("No media signature found");

        if (proofFile == null)
            throw new ProofException("No proof json found");

        if (proofFileSig == null)
            throw new ProofException("No proof json signature found");

        if (pubKey == null)
            throw new ProofException("No public key pubkey.asc found");

        PGPPublicKey pgpPublicKey = PgpUtils.getPublicKey(pubKey);

        boolean proofSigVerified = ProofMode.verifySignature(context, proofFile, proofFileSig, pgpPublicKey);
        if (!proofSigVerified)
            throw new ProofException("Proof json signature not valid");

        boolean mediaSigVerified = ProofMode.verifySignature(context, mediaFile, mediaSig, pgpPublicKey);
        if (!mediaSigVerified)
            throw new ProofException("Media signature not valid");

        return true;
    }

    private static InputStream copyStream (InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int count;
        int BUFFER = 1024;
        byte[] data = new byte[BUFFER];
        while ((count = is.read(data, 0, BUFFER)) != -1) {
            bos.write(data,0,count);
        }
        return new ByteArrayInputStream(bos.toByteArray());
    }


    public static boolean verifySignature (Context context, InputStream fileStream, InputStream sigStream, PGPPublicKey publicKey) throws Exception {

        //PgpUtils.getInstance(context).
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PgpUtils pu = PgpUtils.getInstance(context,prefs.getString("password",PgpUtils.DEFAULT_PASSWORD));
        return pu.verifyDetachedSignature(fileStream, sigStream, publicKey);
    }

    public static void generateProofZip(Context context, String proofHash) throws IOException {

        File fileDirProof = ProofMode.getProofDir(context, proofHash);
        File[] files = fileDirProof.listFiles();
        File fileZip = new File (fileDirProof.getParent(),fileDirProof.getName() + ".zip");

        BufferedInputStream origin;
        FileOutputStream dest = new FileOutputStream(fileZip);
        ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                dest));
        int BUFFER = 1024;
        byte[] data = new byte[BUFFER];

        for (File proofFile : files) {
            try {
                String fileName = proofFile.getName();
                Timber.d("adding to zip: " + fileName);
                origin = new BufferedInputStream(new FileInputStream(proofFile), BUFFER);
                ZipEntry entry = new ZipEntry(fileName);
                out.putNextEntry(entry);
                int count;

                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
                origin.close();
            }
            catch (Exception e)
            {
                Timber.d(e, "Failed adding URI to zip: " + proofFile.getName());
            }
        }

        Timber.d("Adding public key");
        //add public key
        ZipEntry entry = new ZipEntry(PUBKEY_FILE);
        out.putNextEntry(entry);
        out.write(getPublicKeyString(context).getBytes());

        Timber.d("Zip complete");

        out.close();

    }

}
