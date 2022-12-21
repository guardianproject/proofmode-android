package org.witness.proofmode.service;

import static org.witness.proofmode.ProofMode.OPENPGP_FILE_TAG;
import static org.witness.proofmode.ProofMode.PREFS_DOPROOF;
import static org.witness.proofmode.ProofMode.PROOF_FILE_JSON_TAG;
import static org.witness.proofmode.ProofMode.PROOF_FILE_TAG;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;
import org.witness.proofmode.ProofMode;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.notarization.GoogleSafetyNetNotarizationProvider;
import org.witness.proofmode.notarization.NotarizationListener;
import org.witness.proofmode.notarization.NotarizationProvider;
import org.witness.proofmode.util.DeviceInfo;
import org.witness.proofmode.util.GPSTracker;
import org.witness.proofmode.util.SafetyNetResponse;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Stack;
import java.util.Timer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class MediaWatcher extends BroadcastReceiver {

    private final static String PROOF_BASE_FOLDER = "proofmode/";

    private SharedPreferences mPrefs;

    public final static int PROOF_GENERATION_DELAY_TIME_MS = 60 * 1000; // 30 seconds
    private static MediaWatcher mInstance;

    private ExecutorService mExec = Executors.newFixedThreadPool(1);

    private Context mContext = null;

    private ArrayList<NotarizationProvider> mProviders = new ArrayList<>();

    private MediaWatcher (Context context) {
        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        mContext = context;

        startFileSystemMonitor();
    }

    public static synchronized MediaWatcher getInstance (Context context)
    {
        if (mInstance == null)
            mInstance = new MediaWatcher(context);

        return mInstance;
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {

        mExec.submit(() -> {

            boolean doProof = mPrefs.getBoolean(PREFS_DOPROOF, true);

            if (doProof) {
                Uri tmpUriMedia = intent.getData();
                if (tmpUriMedia == null)
                    tmpUriMedia = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);

                if (tmpUriMedia != null)
                    processUri(tmpUriMedia, true);

            }
        });


    }

    public void addNotarizationProvider (NotarizationProvider provider) {
        mProviders.add(provider);
    }

    public void addDefaultNotarizationProviders () {
        try {

            Class.forName("com.google.android.gms.safetynet.SafetyNetApi");

            //notarize and then write proof so we can include notarization response
            final GoogleSafetyNetNotarizationProvider gProvider = new GoogleSafetyNetNotarizationProvider(mContext);
            mProviders.add(gProvider);
        }
        catch (ClassNotFoundException ce)
        {
            //SafetyNet API not available
        }

        try {
            //this may not be included in the current build
            Class.forName("com.eternitywall.ots.OpenTimestamps");

            final NotarizationProvider nProvider = new org.witness.proofmode.notarization.OpenTimestampsNotarizationProvider();
            mProviders.add(nProvider);
        }
        catch (ClassNotFoundException e)
        {
            //class not available
        }
    }

    public String processUri (Uri uriMedia, boolean autogen) {
        try {
            try {
                String mediaHash = HashUtils.getSHA256FromFileContent(mContext.getContentResolver().openInputStream(uriMedia));
                return processUri(mContext, uriMedia, mediaHash, autogen);

            } catch (FileNotFoundException e) {
                Timber.d( "FileNotFoundException: unable to open inputstream for hashing: %s", uriMedia);
                return null;
            } catch (IllegalStateException ise) {
                Timber.d( "IllegalStateException: unable to open inputstream for hashing: %s", uriMedia);
                return null;
            } catch (SecurityException e) {
                Timber.d( "SecurityException: security exception accessing URI: %s", uriMedia);
                return null;
            }
        }
        catch (RuntimeException re)
        {
            Timber.e(re,"RUNTIME EXCEPTION processing media file: " + re);
            return null;
        }
        catch (Error err)
        {
            Timber.e(err,"FATAL ERROR processing media file: " + err);

            return null;
        }
    }

    public String processUri (Uri fileUri, String proofHash, boolean autogenerated) {
        try {
            return processUri(mContext, fileUri, proofHash, autogenerated);
        }
        catch (FileNotFoundException re)
        {
            Timber.e(re,"FILENOTFOUND EXCEPTION processing media file: " + re);
            return null;
        }
        catch (RuntimeException re)
        {
            Timber.e(re,"RUNTIME EXCEPTION processing media file: " + re);
            return null;
        }
        catch (Error err)
        {
            Timber.e(err,"FATAL ERROR processing media file: " + err);

            return null;
        }
    }

    public String processUri (final Context context, final Uri uriMedia, String mediaHash, boolean autogenerated) throws FileNotFoundException {

        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        final boolean showDeviceIds = mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE,ProofMode.PREF_OPTION_PHONE_DEFAULT);
        final boolean showLocation = mPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION,ProofMode.PREF_OPTION_LOCATION_DEFAULT) && checkPermissionForLocation();
        final boolean autoNotarize = mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT);
        final boolean showMobileNetwork = mPrefs.getBoolean(ProofMode.PREF_OPTION_NETWORK,ProofMode.PREF_OPTION_NETWORK_DEFAULT);

        if (mediaHash != null) {

            try {
                if (proofExists(context,mediaHash))
                    return mediaHash;
            } catch (FileNotFoundException e) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s",mediaHash, uriMedia);

            String notes = "";

            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                String version = pInfo.versionName;
                notes = "ProofMode v" + version + " autogenerated=" + autogenerated;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }


            writeProof(context, uriMedia, context.getContentResolver().openInputStream(uriMedia), mediaHash, showDeviceIds, showLocation, showMobileNetwork, notes);


            if (autoNotarize && isOnline(context)) {

                try {
                    for (NotarizationProvider provider : mProviders) {
                        provider.notarize(mediaHash, context.getContentResolver().openInputStream(uriMedia), new NotarizationListener() {
                            @Override
                            public void notarizationSuccessful(String hash, String result) {
                                Timber.d("Got notarization success response for %s, timestamp: %s", provider.getNotarizationFileExtension(), result);
                                File fileMediaNotarizeData = new File(getHashStorageDir(context, hash), hash + provider.getNotarizationFileExtension());

                                try {
                                    byte[] rawNotarizeData = Base64.decode(result, Base64.DEFAULT);
                                    writeBytesToFile(context, fileMediaNotarizeData, rawNotarizeData);
                                } catch (Exception e) {
                                    //if an error, then just write the bytes
                                    try {
                                        writeBytesToFile(context, fileMediaNotarizeData, result.getBytes("UTF-8"));
                                    } catch (UnsupportedEncodingException ex) {
                                        ex.printStackTrace();
                                    }
                                }
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
        }
        else
        {
            Timber.d("Unable to generated hash of media files, no proof generated");

        }

        return null;
    }

    public String processBytes (final Context context, Uri uriMedia, final byte[] mediaBytes, String mimeType) {

        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        final boolean showDeviceIds = mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE,ProofMode.PREF_OPTION_PHONE_DEFAULT);
        final boolean showLocation = mPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION,ProofMode.PREF_OPTION_LOCATION_DEFAULT) && checkPermissionForLocation();
        final boolean autoNotarize = mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT);
        final boolean showMobileNetwork = mPrefs.getBoolean(ProofMode.PREF_OPTION_NETWORK,ProofMode.PREF_OPTION_NETWORK_DEFAULT);

        String mediaHash = HashUtils.getSHA256FromBytes(mediaBytes);

        if (mediaHash != null) {

            try {
                if (proofExists(context,mediaHash))
                    return mediaHash;
            } catch (FileNotFoundException e) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s",mediaHash, uriMedia);

            String notes = "";

            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                String version = pInfo.versionName;
                notes = "ProofMode v" + version + " autogenerated=" + true;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }


            if (!autoNotarize) {
                //write immediate proof
                writeProof(context, null, new ByteArrayInputStream(mediaBytes), mediaHash, showDeviceIds, showLocation, showMobileNetwork, notes);

            }
            else {

                if (isOnline(context)) {

                        for (NotarizationProvider provider : mProviders)
                        {
                            provider.notarize(mediaHash, new ByteArrayInputStream(mediaBytes), new NotarizationListener() {
                                @Override
                                public void notarizationSuccessful(String hash, String result) {
                                    Timber.d("Got notarization success response timestamp: %s", result);
                                    File fileMediaNotarizeData = new File(getHashStorageDir(context, hash), hash + provider.getNotarizationFileExtension());

                                    byte[] rawNotarizeData = Base64.decode(result, Base64.DEFAULT);
                                    writeBytesToFile(context, fileMediaNotarizeData, rawNotarizeData);

                                }

                                @Override
                                public void notarizationFailed(int errCode, String message) {

                                    Timber.d("Got notarization error response: %s", message);

                                }
                            });
                        }




                }
                else
                {
                    //write immediate proof
                    writeProof(context, uriMedia, new ByteArrayInputStream(mediaBytes), mediaHash, showDeviceIds, showLocation, showMobileNetwork, notes);

                }
            }

            return mediaHash;
        }
        else
        {
            Timber.d("Unable to generated hash of media files, no proof generated");

        }

        return null;
    }

    private boolean proofExists (Context context, String hash) throws FileNotFoundException {
        boolean result = false;

        if (hash != null) {


            File fileFolder = MediaWatcher.getHashStorageDir(context,hash);

            if (fileFolder != null ) {
                File fileMediaProof = new File(fileFolder, hash + PROOF_FILE_TAG);


                if (fileMediaProof.exists()) {
                    Timber.d("Proof EXISTS for hash %s", hash);

                    result = true;
                } else {
                    //generate now?
                    result = false;
                    Timber.d("Proof DOES NOT EXIST for hash %s", hash);


                }
            }
        }

        return result;
    }


    public boolean isOnline(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();
    }


    private void writeProof (Context context, Uri uriMedia, InputStream is, String mediaHash, boolean showDeviceIds, boolean showLocation, boolean showMobileNetwork, String notes)
    {

        boolean usePgpArmor = true;

        File fileFolder = getHashStorageDir(context,mediaHash);

        if (fileFolder != null) {

            File fileMediaProof = new File(fileFolder, mediaHash + PROOF_FILE_TAG);

            File fileMediaProofJson = new File(fileFolder, mediaHash + PROOF_FILE_JSON_TAG);

            try {

                //add data to proof csv and sign again
                boolean writeHeaders = !fileMediaProof.exists();

                HashMap<String,String> hmProof = buildProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, notes);
                writeMapToCSV(context, fileMediaProof, hmProof, writeHeaders);

                JSONObject jProof = new JSONObject(hmProof);
                writeTextToFile(context, fileMediaProofJson, jProof.toString());

                if (fileMediaProof.exists()) {
                    //sign the proof file again
                    PgpUtils.getInstance(context).createDetachedSignature(fileMediaProof, new File(fileFolder, mediaHash + PROOF_FILE_TAG + OPENPGP_FILE_TAG), PgpUtils.DEFAULT_PASSWORD, usePgpArmor);
                }

                if (fileMediaProofJson.exists()) {
                    //sign the proof file again
                    PgpUtils.getInstance(context).createDetachedSignature(fileMediaProofJson, new File(fileFolder, mediaHash + PROOF_FILE_JSON_TAG + OPENPGP_FILE_TAG), PgpUtils.DEFAULT_PASSWORD, usePgpArmor);
                }

                //sign the media file
                File fileMediaSig = new File(fileFolder, mediaHash + OPENPGP_FILE_TAG);
               if ((!fileMediaSig.exists()) || fileMediaSig.length() == 0)
                  PgpUtils.getInstance(context).createDetachedSignature(is, new FileOutputStream(fileMediaSig), PgpUtils.DEFAULT_PASSWORD, usePgpArmor);

                Timber.d("Proof written/updated for uri %s and hash %s", uriMedia, mediaHash);

            } catch (Exception e) {
                Timber.d( "Error signing media or proof: %s", e.getLocalizedMessage());
            }
        }
    }

    public static File getHashStorageDir(Context context, String hash) {

        // Get the directory for the user's public pictures directory.
        File fileParentDir = new File(context.getFilesDir(),PROOF_BASE_FOLDER);
        if (!fileParentDir.exists()) {
            fileParentDir.mkdir();
        }

        /**
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            fileParentDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), PROOF_BASE_FOLDER);

        }
        else
        {
            fileParentDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), PROOF_BASE_FOLDER);
        }

        if (!fileParentDir.exists()) {
            if (!fileParentDir.mkdir())
            {
                fileParentDir = new File(Environment.getExternalStorageDirectory(), PROOF_BASE_FOLDER);
                if (!fileParentDir.exists())
                    if (!fileParentDir.mkdir())
                        return null;
            }
        }**/

        File fileHashDir = new File(fileParentDir, hash + '/');
        if (!fileHashDir.exists())
            if (!fileHashDir.mkdir())
                return null;

        return fileHashDir;
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    private HashMap<String,String> buildProof (Context context, Uri uriMedia, String mediaHash, boolean showDeviceIds, boolean showLocation, boolean showMobileNetwork, String notes)
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
                }
                catch (Exception e)
                {
                    mediaPath = uriMedia.toString();
                }
            }
        }

        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.FULL,DateFormat.FULL);

        HashMap<String, String> hmProof = new HashMap<>();

        if (mediaPath != null)
            hmProof.put("File Path",mediaPath);
        else
            hmProof.put("File Path",uriMedia.toString());

        hmProof.put("File Hash SHA256",mediaHash);

        if (mediaPath != null)
            hmProof.put("File Modified",df.format(new Date(new File(mediaPath).lastModified())));

        hmProof.put("Proof Generated",df.format(new Date()));

        if (showDeviceIds) {
            hmProof.put("DeviceID", DeviceInfo.getDeviceId(context));
            hmProof.put("Wifi MAC", DeviceInfo.getWifiMacAddr());
        }

        hmProof.put("IPv4",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV4));
        hmProof.put("IPv6",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV6));

        hmProof.put("DataType",DeviceInfo.getDataType(context));
        hmProof.put("Network",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_NETWORK));

        hmProof.put("NetworkType",DeviceInfo.getNetworkType(context));
        hmProof.put("Hardware",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_HARDWARE_MODEL));
        hmProof.put("Manufacturer",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_MANUFACTURE));
        hmProof.put("ScreenSize",DeviceInfo.getDeviceInch(context));

        hmProof.put("Language",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LANGUAGE));
        hmProof.put("Locale",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LOCALE));



        if (showLocation)
        {
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
                    hmProof.put("Location.Latitude", loc.getLatitude() + "");
                    hmProof.put("Location.Longitude", loc.getLongitude() + "");
                    hmProof.put("Location.Provider", loc.getProvider());
                    hmProof.put("Location.Accuracy", loc.getAccuracy() + "");
                    hmProof.put("Location.Altitude", loc.getAltitude() + "");
                    hmProof.put("Location.Bearing", loc.getBearing() + "");
                    hmProof.put("Location.Speed", loc.getSpeed() + "");
                    hmProof.put("Location.Time", loc.getTime() + "");
                }
                else
                {
                    hmProof.put("Location.Latitude", "");
                    hmProof.put("Location.Longitude", "");
                    hmProof.put("Location.Provider", "none");
                    hmProof.put("Location.Accuracy", "");
                    hmProof.put("Location.Altitude", "");
                    hmProof.put("Location.Bearing", "");
                    hmProof.put("Location.Speed", "");
                    hmProof.put("Location.Time", "");
                }

            }

            if (showMobileNetwork)
                hmProof.put("CellInfo", DeviceInfo.getCellInfo(context));
            else
                hmProof.put("CellInfo", "none");

        }
        else
        {
            hmProof.put("Location.Latitude", "");
            hmProof.put("Location.Longitude", "");
            hmProof.put("Location.Provider", "none");
            hmProof.put("Location.Accuracy", "");
            hmProof.put("Location.Altitude", "");
            hmProof.put("Location.Bearing", "");
            hmProof.put("Location.Speed", "");
            hmProof.put("Location.Time", "");
        }

        hmProof.put("SafetyCheck", "false");
        hmProof.put("SafetyCheckBasicIntegrity", "");
        hmProof.put("SafetyCheckCtsMatch", "");
        hmProof.put("SafetyCheckTimestamp", "");

        /**
        if (resp != null) {
            hmProof.put("SafetyCheck", "true");
            hmProof.put("SafetyCheckBasicIntegrity", resp.isBasicIntegrity()+"");
            hmProof.put("SafetyCheckCtsMatch", resp.isCtsProfileMatch()+"");
            hmProof.put("SafetyCheckTimestamp", resp.getTimestampMs()+"");
        }
        else
        {
            hmProof.put("SafetyCheck", "false");
            hmProof.put("SafetyCheckBasicIntegrity", "");
            hmProof.put("SafetyCheckCtsMatch", "");
            hmProof.put("SafetyCheckTimestamp", "");
        }**/

        if (!TextUtils.isEmpty(notes))
            hmProof.put("Notes",notes);
        else
            hmProof.put("Notes","");

        return hmProof;

    }

    private static void writeBytesToFile (Context context, File fileOut, byte[] data)
    {
        try {
            DataOutputStream os = new DataOutputStream(new FileOutputStream(fileOut,true));
            os.write(data);
            os.flush();
            os.close();
        }
        catch (IOException ioe)
        {
            ioe.printStackTrace();
        }

    }

    private static void writeMapToCSV (Context context, File fileOut, HashMap<String,String> hmProof, boolean writeHeaders)
    {

        StringBuffer sb = new StringBuffer();

        if (writeHeaders) {
            for (String key : hmProof.keySet()) {
                sb.append(key).append(",");
            }

            sb.append("\n");
        }

        for (String key : hmProof.keySet())
        {
            String value = hmProof.get(key);
            value = value.replace(',',' '); //remove commas from CSV file
            sb.append(value).append(",");
        }

        writeTextToFile(context, fileOut, sb.toString());
    }

    private static void writeTextToFile (Context context, File fileOut, String text)
    {
        try {
            PrintStream ps = new PrintStream(new FileOutputStream(fileOut,true));
            ps.println(text);
            ps.flush();
            ps.close();
        }
        catch (IOException ioe)
        {
            ioe.printStackTrace();
        }

    }

    private static String getSHA256FromFileContent(String filename)
    {

        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; //created at start.
            InputStream fis = new FileInputStream(filename);
            int n = 0;
            while (n != -1)
            {
                n = fis.read(buffer);
                if (n > 0)
                {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] digestResult = digest.digest();
            return asHex(digestResult);
        }
        catch (Exception e)
        {
            return null;
        }
    }


    private static String getSHA256FromFileContent(InputStream fis)
    {

        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; //created at start.
            int n = 0;
            while (n != -1)
            {
                n = fis.read(buffer);
                if (n > 0)
                {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] digestResult = digest.digest();
            return asHex(digestResult);
        }
        catch (Exception e)
        {
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

    private static final int READ_STORAGE_PERMISSION_REQUEST_CODE = 41;
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

  //  public static FileObserver observerMedia;

    public Stack<String> qMedia = new Stack<>();
    public Timer qTimer = null;

    private void startFileSystemMonitor() {

        if (checkPermissionForReadExternalStorage()) {

            String pathToWatch = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();

            /**
            observerMedia = new RecursiveFileObserver(pathToWatch, FileObserver.MODIFY|FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO) { // set up a file observer to watch this directory on sd card
                @Override
                public void onEvent(int event, final String mediaPath) {
                    if (mediaPath != null && (!mediaPath.equals(".probe"))) { // check that it's not equal to .probe because thats created every time camera is launched

                        if (!qMedia.contains(mediaPath))
                            qMedia.push(mediaPath);

                        if (qTimer != null)
                        {
                            qTimer.cancel();
                            qTimer.purge();
                            qTimer = null;
                        }

                        qTimer = new Timer();
                        qTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {

                                while (!qMedia.isEmpty())
                                {
                                    File fileMedia = new File(qMedia.pop());
                                    if (fileMedia.exists())
                                        processUri(Uri.fromFile(fileMedia), true);
                                }

                            }
                        }, MediaWatcher.PROOF_GENERATION_DELAY_TIME_MS);

                    }
                }
            };
            observerMedia.startWatching();
            **/

        }


    }

    public void stop () {

        /**
        if (observerMedia != null)
        {
            observerMedia.stopWatching();
        }**/
    }
}
