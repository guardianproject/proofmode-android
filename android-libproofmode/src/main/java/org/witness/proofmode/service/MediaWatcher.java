package org.witness.proofmode.service;

import static org.witness.proofmode.ProofMode.EVENT_PROOF_EXTRA_HASH;
import static org.witness.proofmode.ProofMode.EVENT_PROOF_EXTRA_URI;
import static org.witness.proofmode.ProofMode.EVENT_PROOF_FAILED;
import static org.witness.proofmode.ProofMode.EVENT_PROOF_GENERATED;
import static org.witness.proofmode.ProofMode.OPENPGP_FILE_TAG;
import static org.witness.proofmode.ProofMode.PREFS_DOPROOF;
import static org.witness.proofmode.ProofMode.PROOF_FILE_JSON_TAG;
import static org.witness.proofmode.ProofMode.PROOF_FILE_TAG;
import static org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE;
import static org.witness.proofmode.ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.location.Location;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.TextUtils;

import org.bouncycastle.openpgp.PGPException;
import org.json.JSONObject;
import org.witness.proofmode.ProofMode;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.pgp.PgpUtils;
import org.witness.proofmode.storage.DefaultStorageProvider;
import org.witness.proofmode.storage.CompositeStorageProvider;
import org.witness.proofmode.storage.FilebaseStorageProvider;
import org.witness.proofmode.storage.FilebaseConfig;
import org.witness.proofmode.notarization.NotarizationListener;
import org.witness.proofmode.notarization.NotarizationProvider;
import org.witness.proofmode.storage.StorageListener;
import org.witness.proofmode.storage.StorageProvider;
import org.witness.proofmode.util.DeviceInfo;
import org.witness.proofmode.util.GPSTracker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class MediaWatcher extends BroadcastReceiver implements ProofModeV1Constants {

    public static final String UTF_8 = "UTF-8";
    public final static int PROOF_GENERATION_DELAY_TIME_MS = 500; // 30 seconds

    private static MediaWatcher mInstance;
    private SharedPreferences mPrefs;
    private final ExecutorService mExec = Executors.newFixedThreadPool(1);
    private Context mContext = null;
    private String mPassphrase = null;
    private ArrayList<NotarizationProvider> mProviders = new ArrayList<>();

    private StorageProvider mStorageProvider = null;

    public MediaWatcher () {


    }

    private void init(Context context, StorageProvider storageProvider) {
        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        mContext = context;

        if (storageProvider != null)
            mStorageProvider = storageProvider;
        else
            mStorageProvider = createCompositeStorageProvider(mContext);

        mPassphrase = mPrefs.getString(PREFS_KEY_PASSPHRASE, PREFS_KEY_PASSPHRASE_DEFAULT);

    }

    public void setStorageProvider (StorageProvider storageProvider)
    {
        mStorageProvider = storageProvider;
    }

    public StorageProvider getStorageProvider ()
    {
        return mStorageProvider;
    }
    
    private StorageProvider createCompositeStorageProvider(Context context) {
        DefaultStorageProvider primaryProvider = new DefaultStorageProvider(context);
        
        // Check if Filebase is configured and enabled
        FilebaseConfig config = getFilebaseConfig();
        if (config.getEnabled() && config.isValid()) {
            try {
                FilebaseStorageProvider filebaseProvider = new FilebaseStorageProvider(
                    config.getAccessKey(),
                    config.getSecretKey(), 
                    config.getBucketName(),
                    config.getEndpoint()
                );
                return new CompositeStorageProvider(primaryProvider, filebaseProvider);
            } catch (Exception e) {
                android.util.Log.e("MediaWatcher", "Failed to initialize Filebase provider", e);
            }
        }
        
        return primaryProvider;
    }
    
    private FilebaseConfig getFilebaseConfig() {
        boolean enabled = mPrefs.getBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, false);
        String accessKey = mPrefs.getString(FilebaseConfig.PREF_FILEBASE_ACCESS_KEY, "");
        String secretKey = mPrefs.getString(FilebaseConfig.PREF_FILEBASE_SECRET_KEY, "");
        String bucketName = mPrefs.getString(FilebaseConfig.PREF_FILEBASE_BUCKET_NAME, "");
        String endpoint = mPrefs.getString(FilebaseConfig.PREF_FILEBASE_ENDPOINT, "https://s3.filebase.com");
        
        return new FilebaseConfig(accessKey, secretKey, bucketName, endpoint, enabled);
    }

    public static synchronized MediaWatcher getInstance(Context context) {

        if (mInstance == null) {
            mInstance = new MediaWatcher();
            mInstance.init(context, null);
        }

        return mInstance;
    }


/*
// TODO Involes writing
 */
    private void writeMapToCSV(Context context, String mediaHash, String identifier, HashMap<String, String> hmProof, boolean writeHeaders) {

        StringBuffer sb = new StringBuffer();

        if (writeHeaders) {
            for (String key : hmProof.keySet()) {
                sb.append(key).append(",");
            }

            sb.append("\n");
        }

        for (String key : hmProof.keySet()) {
            String value = hmProof.get(key);
            value = value.replace(',', ' '); //remove commas from CSV file
            sb.append(value).append(",");
        }

        mStorageProvider.saveText(mediaHash, identifier, sb.toString(), null);
    }



    private static String getSHA256FromFileContent(String filename) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; //created at start.
            InputStream fis = new FileInputStream(filename);
            int n = 0;
            while (n != -1) {
                n = fis.read(buffer);
                if (n > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] digestResult = digest.digest();
            return asHex(digestResult);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getSHA256FromFileContent(InputStream fis) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; //created at start.
            int n = 0;
            while (n != -1) {
                n = fis.read(buffer);
                if (n > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] digestResult = digest.digest();
            return asHex(digestResult);
        } catch (Exception e) {
            return null;
        }
    }

    private static String asHex(byte[] arrayBytes) {
        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < arrayBytes.length; i++) {
            stringBuffer.append(Integer.toString((arrayBytes[i] & 0xff) + 0x100, 16)
                    .substring(1));
        }
        return stringBuffer.toString();
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {

        init (context, null);

        mExec.submit(() -> {

            boolean doProof = mPrefs.getBoolean(PREFS_DOPROOF, true);

            if (doProof) {
                Uri tmpUriMedia = intent.getData();
                if (tmpUriMedia == null)
                    tmpUriMedia = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);

                if (tmpUriMedia != null)
                    processUri(tmpUriMedia, true, null);

            }
        });


    }

    public void addNotarizationProvider(NotarizationProvider provider) {
        mProviders.add(provider);
    }

    public ExecutorService singleThreaded() {
        return mExec;
    }

    public interface QueueMediaCallback {
        void processUriDone(String hash);
    }

    public void queueMedia(Uri uriMedia, boolean autogen, Date createdAt, QueueMediaCallback callback) {
        var looper = Looper.myLooper();
        if (looper == null) {
            looper = mContext.getMainLooper();
        }
        Handler handler = new Handler(looper);
        mExec.submit(() -> {
            var hash = processUri(uriMedia, autogen, createdAt);
            Runnable myRunnable = new Runnable() {
                @Override
                public void run() {
                    callback.processUriDone(hash);
                }
            };
            handler.post(myRunnable);
        });
    }

    public String processUri (Uri uriMedia, boolean autogen, Date createdAt) {

        Intent intent = new Intent();
        intent.setPackage("org.witness.proofmode");

        try {


            try {


                String mediaHash = HashUtils.getSHA256FromFileContent(mContext.getContentResolver().openInputStream(uriMedia));
                String resultHash = processUri(mContext, uriMedia, mediaHash, autogen, createdAt);

                if (resultHash != null) {
                    //send generated event
                    intent.setAction(EVENT_PROOF_GENERATED);
                    intent.putExtra(EVENT_PROOF_EXTRA_URI, uriMedia.toString());
                    intent.putExtra(EVENT_PROOF_EXTRA_HASH, resultHash);
                    mContext.sendBroadcast(intent);
                }

                return resultHash;

            } catch (FileNotFoundException e) {
                Timber.e(e, "FileNotFoundException: unable to open inputstream for hashing");
                intent.setAction(EVENT_PROOF_FAILED);
                intent.putExtra(EVENT_PROOF_EXTRA_URI, uriMedia.toString());
                mContext.sendBroadcast(intent);
                return null;
            } catch (IllegalStateException ise) {
                Timber.e(ise,"IllegalStateException: unable to open inputstream for hashing");
                intent.setAction(EVENT_PROOF_FAILED);
                intent.putExtra(EVENT_PROOF_EXTRA_URI, uriMedia.toString());
                mContext.sendBroadcast(intent);
                return null;
            } catch (PGPException | IOException | SecurityException e) {
                Timber.e(e,"SecurityException: security exception accessing URI");
                intent.setAction(EVENT_PROOF_FAILED);
                intent.putExtra(EVENT_PROOF_EXTRA_URI, uriMedia.toString());
                mContext.sendBroadcast(intent);
                return null;
            }

        } catch (RuntimeException re) {
            Timber.e(re, "RUNTIME EXCEPTION processing media file");
            intent.setAction(EVENT_PROOF_FAILED);
            intent.putExtra("proof",uriMedia.toString());
            mContext.sendBroadcast(intent);
            return null;
        } catch (Error err) {
            Timber.e(err, "FATAL ERROR processing media file");
            intent.setAction(EVENT_PROOF_FAILED);
            intent.putExtra("proof",uriMedia.toString());
            mContext.sendBroadcast(intent);
            return null;
        }
    }

    public String processUri(Uri fileUri, String proofHash, boolean autogenerated, Date createdAt) {
        try {
            return processUri(mContext, fileUri, proofHash, autogenerated, createdAt);
        } catch (FileNotFoundException re) {
            Timber.e(re, "FILENOTFOUND EXCEPTION processing media file");
            return null;
        } catch (PGPException e) {
            Timber.e(e, "PGPException EXCEPTION processing media file");
            return null;
        } catch (IOException e) {
            Timber.e(e, "IOException EXCEPTION processing media file");
            return null;
        } catch (RuntimeException re) {
            Timber.e(re, "RUNTIME EXCEPTION processing media file");
            return null;
        } catch (Error err) {
            Timber.e(err, "FATAL ERROR processing media file");

            return null;
        }
    }

    public String processUri(final Context context, final Uri uriMedia, String mediaHash, boolean autogenerated, Date createdAt) throws IOException, PGPException {

        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        final boolean showDeviceIds = mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT);
        final boolean showLocation = mPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, ProofMode.PREF_OPTION_LOCATION_DEFAULT) && checkPermissionForLocation();
        final boolean autoNotarize = mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT);
        final boolean showMobileNetwork = mPrefs.getBoolean(ProofMode.PREF_OPTION_NETWORK, ProofMode.PREF_OPTION_NETWORK_DEFAULT);

        if (mediaHash != null) {

            try {
                if (proofExists(mediaHash))
                    return null;
            } catch (FileNotFoundException e) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s", mediaHash, uriMedia);

            String notes = "";

            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                String version = pInfo.versionName;
                notes = "ProofMode v" + version + " autogenerated=" + autogenerated;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }


            writeProof(context, uriMedia, context.getContentResolver().openInputStream(uriMedia), mediaHash, showDeviceIds, showLocation, showMobileNetwork, notes, createdAt);


            if (autoNotarize && isOnline(context)) {

                try {
                    for (NotarizationProvider provider : mProviders) {

                        ContentResolver cr = context.getContentResolver();
                        InputStream is = cr.openInputStream(uriMedia);
                        String mimeType = cr.getType(uriMedia);

                        provider.notarize(mediaHash, mimeType, is, new NotarizationListener() {
                            @Override
                            public void notarizationSuccessful(String hash, String result) {
                                Timber.d("Got notarization success response for %s", provider.getNotarizationFileExtension());

                                try {
                                    mStorageProvider.saveBytes(hash, hash + provider.getNotarizationFileExtension(), result.getBytes(StandardCharsets.UTF_8), new StorageListener() {
                                        @Override
                                        public void saveSuccessful(String hash, String uri) {}

                                        @Override
                                        public void saveFailed(Exception exception) {}
                                    });

                                } catch (Exception e) {
                                        e.printStackTrace();

                                }
                            }

                            @Override
                            public void notarizationSuccessful(String hash, File fileTmp) {
                                Timber.d("Got notarization success response for %s", fileTmp.getName());
                                String ext = fileTmp.getName().split(".")[1];

                                try {
                                    mStorageProvider.saveStream(hash, hash + '.' + ext, new FileInputStream(fileTmp), new StorageListener() {
                                        @Override
                                        public void saveSuccessful(String hash, String uri) {}

                                        @Override
                                        public void saveFailed(Exception exception) {}
                                    });
                                } catch (FileNotFoundException e) {
                                    throw new RuntimeException(e);
                                }

                            }

                            @Override
                            public void notarizationSuccessful(String hash, byte[] result) {
                                Timber.d("Got notarization success response for %s, timestamp: %s", provider.getNotarizationFileExtension(), result);
                                mStorageProvider.saveBytes(hash, hash + provider.getNotarizationFileExtension(), result, new StorageListener() {
                                    @Override
                                    public void saveSuccessful(String hash, String uri) {}

                                    @Override
                                    public void saveFailed(Exception exception) {}
                                });

                            }

                            @Override
                            public void notarizationFailed(int errCode, String message) {

                                Timber.d("Got notarization error response for %s: %s", provider.getNotarizationFileExtension(), message);

                            }
                        });
                    }

                } catch (FileNotFoundException e) {

                    Timber.e(e);
                }

            }


            return mediaHash;
        } else {
            Timber.d("Unable to generated hash of media files, no proof generated");

        }

        return null;
    }

    public String processBytes(final Context context, Uri uriMedia, final byte[] mediaBytes, String mimeType, Date createdAt) throws PGPException, IOException {

        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        final boolean showDeviceIds = mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT);
        final boolean showLocation = mPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, ProofMode.PREF_OPTION_LOCATION_DEFAULT) && checkPermissionForLocation();
        final boolean autoNotarize = mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT);
        final boolean showMobileNetwork = mPrefs.getBoolean(ProofMode.PREF_OPTION_NETWORK, ProofMode.PREF_OPTION_NETWORK_DEFAULT);

        String mediaHash = HashUtils.getSHA256FromBytes(mediaBytes);

        if (mediaHash != null) {

            try {
                if (proofExists(mediaHash))
                    return mediaHash;
            } catch (FileNotFoundException e) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s", mediaHash, uriMedia);

            String notes = "";

            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                String name = pInfo.packageName;
                String version = pInfo.versionName;

                notes = name + " v" + version + " autogenerated=" + true;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }

            //write immediate proof
            writeProof(context, uriMedia, new ByteArrayInputStream(mediaBytes), mediaHash, showDeviceIds, showLocation, showMobileNetwork, notes, null);

            if (autoNotarize && isOnline(context)) {

                for (NotarizationProvider provider : mProviders) {
                    provider.notarize(mediaHash, mimeType, new ByteArrayInputStream(mediaBytes), new NotarizationListener() {
                        @Override
                        public void notarizationSuccessful(String hash, String result) {
                            Timber.d("Got notarization success response timestamp: %s", result);
                            try {
                                mStorageProvider.saveBytes(hash, hash + provider.getNotarizationFileExtension(), result.getBytes(StandardCharsets.UTF_8), new StorageListener() {
                                    @Override
                                    public void saveSuccessful(String hash, String uri) {}

                                    @Override
                                    public void saveFailed(Exception exception) {}
                                });

                            } catch (Exception e) {
                                e.printStackTrace();

                            }

                        }

                        @Override
                        public void notarizationSuccessful(String hash, File fileTmp) {
                            Timber.d("Got notarization success response for %s", fileTmp.getName());

                            String ext = fileTmp.getName().split(".")[1];
                            try {
                                mStorageProvider.saveStream(hash, hash + '.' + ext, new FileInputStream(fileTmp), new StorageListener() {
                                    @Override
                                    public void saveSuccessful(String hash, String uri) {}

                                    @Override
                                    public void saveFailed(Exception exception) {}
                                });
                            } catch (FileNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void notarizationSuccessful(String hash, byte[] result) {
                            Timber.d("Got notarization success response for %s, timestamp: %s", provider.getNotarizationFileExtension(), result);
                            mStorageProvider.saveBytes(hash, hash + provider.getNotarizationFileExtension(), result, new StorageListener() {
                                @Override
                                public void saveSuccessful(String hash, String uri) {}

                                @Override
                                public void saveFailed(Exception exception) {}
                            });
                        }

                        @Override
                        public void notarizationFailed(int errCode, String message) {

                            Timber.d("Got notarization error response: %s", message);

                        }
                    });
                }


            }

            return mediaHash;
        } else {
            Timber.d("Unable to generated hash of media files, no proof generated");

        }

        return null;
    }

    public String processFileDescriptor(final Context context, Uri uriMedia, final FileDescriptor fdMedia, String mimeType) throws IOException, PGPException {

        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        final boolean showDeviceIds = mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT);
        final boolean showLocation = mPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION, ProofMode.PREF_OPTION_LOCATION_DEFAULT) && checkPermissionForLocation();
        final boolean autoNotarize = mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT);
        final boolean showMobileNetwork = mPrefs.getBoolean(ProofMode.PREF_OPTION_NETWORK, ProofMode.PREF_OPTION_NETWORK_DEFAULT);

        InputStream isMedia = new FileInputStream(fdMedia);
        String mediaHash = HashUtils.getSHA256FromFileContent(isMedia);
        isMedia.close();

        if (mediaHash != null) {

            try {
                if (proofExists(mediaHash))
                    return mediaHash;
            } catch (FileNotFoundException e) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s", mediaHash, uriMedia);

            String notes = "";

            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                String version = pInfo.versionName;
                notes = "ProofMode v" + version + " autogenerated=" + true;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }

            //write immediate proof
            isMedia = new FileInputStream(fdMedia);
            writeProof(context, uriMedia, isMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, notes, null);
            isMedia.close();

            if (autoNotarize && isOnline(context)) {

                for (NotarizationProvider provider : mProviders) {
                    InputStream isMediaNotarize = new FileInputStream(fdMedia);
                    provider.notarize(mediaHash, mimeType, isMediaNotarize, new NotarizationListener() {
                        @Override
                        public void notarizationSuccessful(String hash, String result) {
                            Timber.d("Got notarization success response timestamp: %s", result);
                            try {
                                mStorageProvider.saveBytes(hash, hash + provider.getNotarizationFileExtension(), result.getBytes(StandardCharsets.UTF_8), new StorageListener() {
                                    @Override
                                    public void saveSuccessful(String hash, String uri) {}

                                    @Override
                                    public void saveFailed(Exception exception) {}
                                });

                            } catch (Exception e) {
                                e.printStackTrace();

                            }

                        }

                        @Override
                        public void notarizationSuccessful(String hash, File fileTmp) {
                            Timber.d("Got notarization success response for %s", fileTmp.getName());

                            String ext = fileTmp.getName().split(".")[1];
                            try {
                                mStorageProvider.saveStream(hash, hash + '.' + ext, new FileInputStream(fileTmp), new StorageListener() {
                                    @Override
                                    public void saveSuccessful(String hash, String uri) {}

                                    @Override
                                    public void saveFailed(Exception exception) {}
                                });
                            } catch (FileNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void notarizationSuccessful(String hash, byte[] result) {
                            Timber.d("Got notarization success response for %s, timestamp: %s", provider.getNotarizationFileExtension(), result);
                            mStorageProvider.saveBytes(hash, hash + provider.getNotarizationFileExtension(), result, new StorageListener() {
                                @Override
                                public void saveSuccessful(String hash, String uri) {}

                                @Override
                                public void saveFailed(Exception exception) {}
                            });
                        }

                        @Override
                        public void notarizationFailed(int errCode, String message) {

                            Timber.d("Got notarization error response: %s", message);

                        }
                    });
                }


            }

            return mediaHash;
        } else {
            Timber.d("Unable to generated hash of media files, no proof generated");

        }

        return null;
    }

    public String generateHash(Uri uri) throws FileNotFoundException {
        return HashUtils.getSHA256FromFileContent(mContext.getContentResolver().openInputStream(uri));
    }

    public boolean proofExists(String hash) throws FileNotFoundException {

        if (hash != null)
            return mStorageProvider.proofExists(hash);
        else
            return false;
    }

    public boolean isOnline(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();
    }

    private void writeProof(Context context, Uri uriMedia, InputStream is, String mediaHash, boolean showDeviceIds, boolean showLocation, boolean showMobileNetwork, String notes, Date createdAt) throws PGPException, IOException {

        boolean usePgpArmor = true;

//        File fileMediaProof = new File(fileFolder, mediaHash + PROOF_FILE_TAG);

        boolean proofExists = mStorageProvider.proofExists(mediaHash);

        //add data to proof csv and sign again
        boolean writeHeaders = !proofExists;

        HashMap<String, String> hmProof = buildProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, notes, createdAt);

        writeMapToCSV(context, mediaHash, mediaHash + PROOF_FILE_TAG, hmProof, writeHeaders);

        JSONObject jProof = new JSONObject(hmProof);
        mStorageProvider.saveText(mediaHash, mediaHash + PROOF_FILE_JSON_TAG, jProof.toString(), null);

        PgpUtils pu = PgpUtils.getInstance();

        //sign the proof csv file
        InputStream isProof = mStorageProvider.getInputStream(mediaHash, mediaHash + PROOF_FILE_TAG);
//        OutputStream osProofSig = mStorageProvider.getOutputStream(mediaHash, mediaHash + PROOF_FILE_TAG + OPENPGP_FILE_TAG);
        ByteArrayOutputStream osProofSig = new ByteArrayOutputStream();
        pu.createDetachedSignature(isProof, osProofSig, mPassphrase, usePgpArmor);
        mStorageProvider.saveBytes(mediaHash, mediaHash + PROOF_FILE_TAG + OPENPGP_FILE_TAG, osProofSig.toByteArray(), null);

        //sign the proof json file
        InputStream isProofJson = mStorageProvider.getInputStream(mediaHash, mediaHash + PROOF_FILE_JSON_TAG);
        //OutputStream osProofJsonSig = mStorageProvider.getOutputStream(mediaHash, mediaHash + PROOF_FILE_JSON_TAG + OPENPGP_FILE_TAG);
        ByteArrayOutputStream osProofJsonSig = new ByteArrayOutputStream();
        pu.createDetachedSignature(isProofJson, osProofJsonSig, mPassphrase, usePgpArmor);
        mStorageProvider.saveBytes(mediaHash, mediaHash + PROOF_FILE_JSON_TAG + OPENPGP_FILE_TAG, osProofJsonSig.toByteArray(), null);

        //sign the media file
        //OutputStream osMediaSig = mStorageProvider.getOutputStream(mediaHash, mediaHash + OPENPGP_FILE_TAG);
        ByteArrayOutputStream osMediaSig = new ByteArrayOutputStream();
        pu.createDetachedSignature(is, osMediaSig, mPassphrase, usePgpArmor);
        mStorageProvider.saveBytes(mediaHash, mediaHash + OPENPGP_FILE_TAG, osMediaSig.toByteArray(), null);

        Timber.d("Proof written/updated for uri %s and hash %s", uriMedia, mediaHash);

    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    public static String getMediaPath (Context context, Uri uriMedia)
    {
        String mediaPath = null;

        if (uriMedia != null) {
            if (uriMedia.getScheme() == null || uriMedia.getScheme().equalsIgnoreCase("file")) {
                mediaPath = uriMedia.getPath();
            } else {
                try {
                    String[] projection = {MediaStore.Images.Media.DATA};

                    Cursor cursor = context.getContentResolver().query(uriMedia, projection, null, null, null);

                    if (cursor != null) {
                        if (cursor.getCount() > 0) {

                            cursor.moveToFirst();
                            int colIdx = cursor.getColumnIndex(projection[0]);
                            if (colIdx > -1)
                                mediaPath = cursor.getString(colIdx);
                        }

                        cursor.close();
                    } else {
                        mediaPath = uriMedia.toString();
                    }
                } catch (Exception e) {
                    mediaPath = uriMedia.toString();
                }
            }
        }

        return mediaPath;
    }

    private HashMap<String, String> buildProof(Context context, Uri uriMedia, String mediaHash, boolean showDeviceIds, boolean showLocation, boolean showMobileNetwork, String notes, Date createdAt) {
        String mediaPath = getMediaPath(context, uriMedia);

       TimeZone tz = TimeZone.getDefault();
        DateFormat df = new SimpleDateFormat(ISO_DATE_TIME_FORMAT, Locale.US); // Quoted "Z" to indicate UTC, no timezone offset
        df.setTimeZone(tz);

        HashMap<String, String> hmProof = new HashMap<>();

        if (mediaPath != null)
            hmProof.put(FILE_PATH, mediaPath);
        else
            hmProof.put(FILE_PATH, uriMedia.toString());

        hmProof.put(FILE_HASH_SHA_256, mediaHash);

        if (createdAt != null)
            hmProof.put(FILE_CREATED, df.format(createdAt));
        else if (mediaPath != null) {
            File fileMedia = new File(mediaPath);
            if (fileMedia.exists()) {

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    BasicFileAttributes attr = null;
                    try {
                        attr = Files.readAttributes(fileMedia.toPath(), BasicFileAttributes.class);
                        long createdAtMs = attr.creationTime().toMillis();
                        hmProof.put(FILE_CREATED, df.format(new Date(createdAtMs)));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            }
        }

        if (mediaPath != null)
            hmProof.put(FILE_MODIFIED, df.format(new Date(new File(mediaPath).lastModified())));

        hmProof.put(PROOF_GENERATED, df.format(new Date()));

        hmProof.put(LANGUAGE, DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LANGUAGE));
        hmProof.put(LOCALE, DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LOCALE));

        if (showDeviceIds) {
            try {
                hmProof.put(DEVICE_ID, DeviceInfo.getDeviceId(context));
                hmProof.put(WIFI_MAC, DeviceInfo.getWifiMacAddr());
                hmProof.put(I_PV_4, DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV4));
                hmProof.put(I_PV_6, DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV6));
                hmProof.put(NETWORK, DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_NETWORK));
                hmProof.put(DATA_TYPE, DeviceInfo.getDataType(context));
                hmProof.put(NETWORK_TYPE, DeviceInfo.getNetworkType(context));
            } catch (SecurityException se) {
            }
        }

        hmProof.put(HARDWARE, DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_HARDWARE_MODEL));
        hmProof.put(MANUFACTURER, DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_MANUFACTURE));
        hmProof.put(SCREEN_SIZE, DeviceInfo.getDeviceInch(context));

        if (showLocation) {
            GPSTracker gpsTracker = new GPSTracker(context);

            if (gpsTracker.canGetLocation()) {

                Location loc = gpsTracker.getLocation();

                int waitIdx = 0;
                while (loc == null && waitIdx < 3) {
                    waitIdx++;
                    try {
                        Thread.sleep(500);
                    } catch (Exception e) {
                    }
                    loc = gpsTracker.getLocation();
                }

                if (loc != null) {
                    hmProof.put(LOCATION_LATITUDE, loc.getLatitude() + "");
                    hmProof.put(LOCATION_LONGITUDE, loc.getLongitude() + "");
                    hmProof.put(LOCATION_PROVIDER, loc.getProvider());
                    hmProof.put(LOCATION_ACCURACY, loc.getAccuracy() + "");
                    hmProof.put(LOCATION_ALTITUDE, loc.getAltitude() + "");
                    hmProof.put(LOCATION_BEARING, loc.getBearing() + "");
                    hmProof.put(LOCATION_SPEED, loc.getSpeed() + "");
                    hmProof.put(LOCATION_TIME, loc.getTime() + "");
                } else {
                    hmProof.put(LOCATION_LATITUDE, "");
                    hmProof.put(LOCATION_LONGITUDE, "");
                    hmProof.put(LOCATION_PROVIDER, "none");
                    hmProof.put(LOCATION_ACCURACY, "");
                    hmProof.put(LOCATION_ALTITUDE, "");
                    hmProof.put(LOCATION_BEARING, "");
                    hmProof.put(LOCATION_SPEED, "");
                    hmProof.put(LOCATION_TIME, "");
                }

            }

            if (showMobileNetwork)
                hmProof.put(CELL_INFO, DeviceInfo.getCellInfo(context));
            else
                hmProof.put(CELL_INFO, "none");

        } else {
            hmProof.put(LOCATION_LATITUDE, "");
            hmProof.put(LOCATION_LONGITUDE, "");
            hmProof.put(LOCATION_PROVIDER, "none");
            hmProof.put(LOCATION_ACCURACY, "");
            hmProof.put(LOCATION_ALTITUDE, "");
            hmProof.put(LOCATION_BEARING, "");
            hmProof.put(LOCATION_SPEED, "");
            hmProof.put(LOCATION_TIME, "");
        }

        hmProof.put(SAFETY_CHECK, "false");
        hmProof.put(SAFETY_CHECK_BASIC_INTEGRITY, "");
        hmProof.put(SAFETY_CHECK_CTS_MATCH, "");
        hmProof.put(SAFETY_CHECK_TIMESTAMP, "");

        if (!TextUtils.isEmpty(notes))
            hmProof.put(NOTES, notes);
        else
            hmProof.put(NOTES, "");

        return hmProof;

    }

    //  public static FileObserver observerMedia;

    public boolean checkPermissionForReadExternalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int result = mContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            return result == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    public boolean checkPermissionForLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int result = mContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
            return result == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public void stop () {
        /**
         if (observerMedia != null)
         {
         observerMedia.stopWatching();
         }**/
    }
}
