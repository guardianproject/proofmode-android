package org.witness.proofmode;

import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.webkit.MimeTypeMap;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.pgp.PgpUtils;
import org.witness.proofmode.notarization.NotarizationProvider;
import org.witness.proofmode.service.CameraEventReceiver;
import org.witness.proofmode.service.MediaWatcher;
import org.witness.proofmode.service.PhotosContentJob;
import org.witness.proofmode.service.VideosContentJob;
import org.witness.proofmode.util.GPSTracker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Security;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import timber.log.Timber;


public class ProofMode {

    public final static String PREF_OPTION_NOTARY = "autoNotarize";
    public final static String PREF_OPTION_LOCATION = "trackLocation";
    public final static String PREF_OPTION_PHONE = "trackDeviceId";
    public final static String PREF_OPTION_NETWORK = "trackMobileNetwork";

    public final static String PREF_OPTION_AI = "blockAI";

    public final static String PREF_OPTION_CREDENTIALS = "addCR";

    public final static String PREF_CREDENTIALS_PRIMARY = "prefCredsPrimary";


    public final static boolean PREF_OPTION_NOTARY_DEFAULT = true;
    public final static boolean PREF_OPTION_LOCATION_DEFAULT = false;
    public final static boolean PREF_OPTION_PHONE_DEFAULT = false;
    public final static boolean PREF_OPTION_NETWORK_DEFAULT = true;

    public final static boolean PREF_OPTION_CREDENTIALS_DEFAULT = true;

    public final static boolean PREF_OPTION_AI_DEFAULT = true;

    public final static String PROOF_FILE_TAG = ".proof.csv";
    public final static String PROOF_FILE_JSON_TAG = ".proof.json";
    public final static String OPENPGP_FILE_TAG = ".asc";
    public final static String OPENTIMESTAMPS_FILE_TAG = ".ots";
    public final static String GOOGLE_SAFETYNET_FILE_TAG = ".gst";

    public final static String PROVIDER_TAG = ".provider";

    public final static String PUBKEY_FILE = "pubkey.asc";

    public final static String C2PA_CERT_FILE = "c2paidentity.cert";
    public final static String PREFS_DOPROOF = "doProof";

    public final static String EVENT_PROOF_START = "org.witness.proofmode.PROOF_START";
    public final static String EVENT_PROOF_GENERATED = "org.witness.proofmode.PROOF_GENERATED";

    public final static String EVENT_PROOF_EXISTS = "org.witness.proofmode.PROOF_EXISTS";

    public final static String EVENT_PROOF_FAILED = "org.witness.proofmode.PROOF_FAILED";
    public final static String EVENT_PROOF_EXTRA_HASH = "org.witness.proofmode.PROOF_HASH";
    public final static String EVENT_PROOF_EXTRA_URI = "org.witness.proofmode.PROOF_URI";
    public final static BouncyCastleProvider sProvider = new BouncyCastleProvider();
    // The File system to store proof on, it could be any storage system like encrypted storage
    // or Google drive it. The implementing app can specify it to avoid saving proof on the default app
    // storage directory
    private static File proofFileSystem = null;
    private static CameraEventReceiver mReceiver;
    private static boolean mInit = false;
    private static GPSTracker mLocationTracker;

    static {
        Security.addProvider(sProvider);
    }

    public static File getProofFileSystem() {
        return proofFileSystem;
    }

    /**
     * The implementing file calls this method with the File system they want proof to be stored on.
     *
     * @param fileSystem: The file system used for saving proof data
     */
    public static void setProofFileSystem(File fileSystem) {
        ProofMode.proofFileSystem = fileSystem;
    }

    public synchronized static void initBackgroundService(Context context) {
        if (!mInit) {
            MediaWatcher.getInstance(context);

            if (Build.VERSION.SDK_INT >= 24) {
                PhotosContentJob.scheduleJob(context);
                VideosContentJob.scheduleJob(context);
            }

            startLocationListener(context);

            mInit = true;
        }
    }

    private static void startLocationListener(Context context) {
        mLocationTracker = new GPSTracker(context);
        mLocationTracker.updateLocation();
    }

    public static void stopBackgroundService (Context context)
    {

        PhotosContentJob.cancelJob(context);
        VideosContentJob.cancelJob(context);

        MediaWatcher.getInstance(context).stop();

        if (mLocationTracker != null)
            mLocationTracker.stopUpdateLocation();
    }

    public static BouncyCastleProvider getProvider() {
        return sProvider;
    }

    public static String generateProof(Context context, Uri uri) {

        return MediaWatcher.getInstance(context).processUri(uri, false, null);

    }

    public static String generateProof(Context context, Uri uri, FileDescriptor fdMediaFile, String mimeType) throws IOException, PGPException {

        return MediaWatcher.getInstance(context).processFileDescriptor(context, uri, fdMediaFile, mimeType);

    }

    public static String generateProof(Context context, Uri uri, byte[] mediaBytes, String mimeType) throws PGPException, IOException {

        return MediaWatcher.getInstance(context).processBytes(context, uri, mediaBytes, mimeType, null);

    }

    public static String generateProof(Context context, Uri uri, byte[] mediaBytes, String mimeType, Date createdAt) throws PGPException, IOException {

        return MediaWatcher.getInstance(context).processBytes(context, uri, mediaBytes, mimeType, createdAt);

    }

    public static String generateProof(Context context, Uri uri, String proofHash) {

        return MediaWatcher.getInstance(context).processUri(uri, proofHash, false, null);

    }

    public static void setProofPoints(Context context, boolean deviceIds, boolean location, boolean networks, boolean notarization) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        SharedPreferences.Editor editor = prefs.edit();

        editor.putBoolean(ProofMode.PREF_OPTION_PHONE, deviceIds);
        editor.putBoolean(ProofMode.PREF_OPTION_LOCATION, location);
        editor.putBoolean(ProofMode.PREF_OPTION_NOTARY, notarization);
        editor.putBoolean(ProofMode.PREF_OPTION_NETWORK, networks);

        editor.apply();

    }

    public static void addNotarizationProvider(Context context, NotarizationProvider provider) {
        MediaWatcher.getInstance(context).addNotarizationProvider(provider);
    }

    public static PGPPublicKey getPublicKey() throws PGPException, IOException {
        PgpUtils pu = PgpUtils.getInstance();
        return pu.getPublicKey();

    }

    public static String getPublicKeyString() throws IOException, PGPException {
        PgpUtils pu = PgpUtils.getInstance();
        return pu.getPublicKeyString();
    }

    public static boolean verifyProofZip(Context context, FileDescriptor proofZip) throws Exception {

        InputStream proofZipStream = new FileInputStream(proofZip);

        try (ZipInputStream zis = new ZipInputStream(proofZipStream)) {

            // list files in zip
            ZipEntry zipEntry = zis.getNextEntry();

            while (zipEntry != null) {

                ByteArrayInputStream mediaFile = null;
                String mediaHashSha256 = null;

                if (!zipEntry.getName().endsWith(File.separator)) {

                    String mimeType = getMimeType(zipEntry.getName());

                    if (mimeType != null && (mimeType.startsWith("audio") || mimeType.startsWith("image") || mimeType.startsWith("video"))) {
                        mediaFile = (ByteArrayInputStream) copyStream(zis);
                        mediaHashSha256 = HashUtils.getSHA256FromFileContent(mediaFile);
                        mediaFile.reset();

                        //reopen stream
                        InputStream proofZipStream2 = new FileInputStream(proofZip);
                        boolean verifiedIntegrity = verifyProofZipIntegrity(context, mediaHashSha256, mediaFile, proofZipStream2);
                        proofZipStream2.close();
                        if (!verifiedIntegrity)
                            return false;
                    }

                }

                zipEntry = zis.getNextEntry();


            }
            zis.closeEntry();

        }

        return true;

    }

    public static boolean verifyProofZip(Context context, Uri proofZipUri) throws Exception {

        InputStream proofZipStream = context.getContentResolver().openInputStream(proofZipUri);

        try (ZipInputStream zis = new ZipInputStream(proofZipStream)) {

            // list files in zip
            ZipEntry zipEntry = zis.getNextEntry();

            while (zipEntry != null) {

                ByteArrayInputStream mediaFile = null;
                String mediaHashSha256 = null;

                if (!zipEntry.getName().endsWith(File.separator)) {

                    String mimeType = getMimeType(zipEntry.getName());

                    if (mimeType != null && (mimeType.startsWith("audio") || mimeType.startsWith("image") || mimeType.startsWith("video"))) {
                        mediaFile = (ByteArrayInputStream) copyStream(zis);
                        mediaHashSha256 = HashUtils.getSHA256FromFileContent(mediaFile);
                        mediaFile.reset();

                        //reopen stream
                        InputStream proofZipStream2 = context.getContentResolver().openInputStream(proofZipUri);
                        boolean verifiedIntegrity = verifyProofZipIntegrity(context, mediaHashSha256, mediaFile, proofZipStream2);
                        proofZipStream2.close();
                        if (!verifiedIntegrity)
                            return false;
                    }

                }

                zipEntry = zis.getNextEntry();


            }
            zis.closeEntry();

        }

        return true;
    }

    private static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    public static boolean verifyProofZip(Context context, String mediaHashSha256, InputStream mediaFile, InputStream proofZipStream) throws Exception {

        boolean verifiedIntegrity = verifyProofZipIntegrity(context, mediaHashSha256, mediaFile, proofZipStream);


        return verifiedIntegrity;
    }

    public static boolean verifyProofZipIntegrity(Context context, String mediaHashSha256, InputStream mediaFile, InputStream proofZipStream) throws Exception {

        InputStream mediaSig = null;
        InputStream proofFile = null;
        InputStream proofFileSig = null;
        InputStream pubKey = null;

        try (ZipInputStream zis = new ZipInputStream(proofZipStream)) {

            // list files in zip
            ZipEntry zipEntry = zis.getNextEntry();

            while (zipEntry != null) {


                if (!zipEntry.getName().endsWith(File.separator)) {

                    if (zipEntry.getName().equals(mediaHashSha256 + OPENPGP_FILE_TAG)) {
                        mediaSig = copyStream(zis);
                    } else if (zipEntry.getName().equals(mediaHashSha256 + PROOF_FILE_TAG)) {
                        //the proof file
                        proofFile = copyStream(zis);
                    } else if (zipEntry.getName().equals(mediaHashSha256 + PROOF_FILE_TAG + OPENPGP_FILE_TAG)) {
                        //the proof file
                        proofFileSig = copyStream(zis);
                    } else if (zipEntry.getName().equals(PUBKEY_FILE)) {
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

        boolean proofSigVerified = ProofMode.verifySignature(proofFile, proofFileSig, pgpPublicKey);
        if (!proofSigVerified)
            throw new ProofException("Proof json signature not valid");

        boolean mediaSigVerified = ProofMode.verifySignature(mediaFile, mediaSig, pgpPublicKey);
        if (!mediaSigVerified)
            throw new ProofException("Media signature not valid");

        return true;
    }

    private static InputStream copyStream(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int count;
        int BUFFER = 1024;
        byte[] data = new byte[BUFFER];
        while ((count = is.read(data, 0, BUFFER)) != -1) {
            bos.write(data, 0, count);
        }
        return new ByteArrayInputStream(bos.toByteArray());
    }

    public static boolean verifySignature(InputStream fileStream, InputStream sigStream, PGPPublicKey publicKey) throws Exception {
             PgpUtils pu = PgpUtils.getInstance();
        return pu.verifyDetachedSignature(fileStream, sigStream, publicKey);
    }

    /**
    public static void generateProofZip(Context context, String proofHash, String passphrase) throws IOException, PGPException {

        File fileDirProof = ProofMode.getProofDir(context, proofHash);
        File[] files = fileDirProof.listFiles();
        File fileZip = new File(fileDirProof.getParent(), fileDirProof.getName() + ".zip");

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
            } catch (Exception e) {
                Timber.d(e, "Failed adding URI to zip: " + proofFile.getName());
            }
        }

        Timber.d("Adding public key");
        //add public key
        ZipEntry entry = new ZipEntry(PUBKEY_FILE);
        out.putNextEntry(entry);
        out.write(getPublicKeyString(context, passphrase).getBytes());

        String C2PA_CERT_PATH = "cr.cert";

        entry = new ZipEntry(C2PA_CERT_FILE);
        out.putNextEntry(entry);
        FileInputStream fisCert = new FileInputStream((new File(context.getFilesDir(), C2PA_CERT_PATH)));
        // Transfer bytes from in to out
        byte[] buf = new byte[1024];
        int len;
        while ((len = fisCert.read(buf)) > 0) {
            out.write(buf, 0, len);
        }

        Timber.d("Zip complete");

        out.close();

    }**/

    public static void checkAndGeneratePublicKeyAsync() {
        Executors.newSingleThreadExecutor().execute(() -> {
            //Background work here
            String pubKey = null;
            try {
                pubKey = PgpUtils.getInstance().getPublicKeyFingerprint();
            } catch (PGPException e) {
                throw new RuntimeException(e);
            }


        });
    }


}
