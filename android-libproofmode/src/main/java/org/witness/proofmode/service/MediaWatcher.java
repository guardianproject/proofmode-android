package org.witness.proofmode.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.safetynet.SafetyNetApi;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import org.witness.proofmode.ProofMode;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.notarization.NotarizationListener;
import org.witness.proofmode.notarization.NotarizationProvider;
import org.witness.proofmode.notarization.OpenTimestampsNotarizationProvider;
import org.witness.proofmode.util.DeviceInfo;
import org.witness.proofmode.util.GPSTracker;
import org.witness.proofmode.util.SafetyNetCheck;
import org.witness.proofmode.util.SafetyNetResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

import static org.witness.proofmode.ProofMode.PREFS_DOPROOF;
import static org.witness.proofmode.ProofMode.PROOF_FILE_TAG;

public class MediaWatcher extends BroadcastReceiver {

    private final static String PROOF_FILE_TAG = ".proof.csv";
    private final static String OPENPGP_FILE_TAG = ".asc";
    private final static String PROOF_BASE_FOLDER = "proofmode/";

    private static boolean mStorageMounted = false;
    private SharedPreferences mPrefs;

    public final static int PROOF_GENERATION_DELAY_TIME_MS = 30 * 1000; // 30 seconds
    private static MediaWatcher mInstance;

    private ExecutorService mExec = Executors.newFixedThreadPool(5);

    private Context mContext = null;

    private MediaWatcher (Context context) {
        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        mContext = context;
    }

    public static synchronized MediaWatcher getInstance (Context context)
    {
        if (mInstance == null)
            mInstance = new MediaWatcher(context);

        return mInstance;
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {


        //wait 10 seconds for any final modifications to the media file, like injection of GPS coordinations into EXIF

        mExec.submit(() -> {

            boolean doProof = mPrefs.getBoolean(PREFS_DOPROOF, true);

            if (doProof)
                handleIntent(context, intent);
        });


    }

    public String processFile (File file) {
        Intent intent = new Intent();
        intent.setData(Uri.fromFile(file));
        return handleIntent(mContext,intent);
    }

    public String processUri (Uri fileUri) {
        Intent intent = new Intent();
        intent.setData(fileUri);
        return handleIntent(mContext,intent);
    }

    public String handleIntent (final Context context, Intent intent) {

        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);


        if (intent.getAction() != null) {
            if (intent.getAction().equals(Intent.ACTION_UMS_CONNECTED)) {
                mStorageMounted = true;
            } else if (intent.getAction().equals(Intent.ACTION_UMS_DISCONNECTED)) {
                mStorageMounted = false;
            }
        }

        Uri tmpUriMedia = intent.getData();
        if (tmpUriMedia == null)
            tmpUriMedia = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);

        if (tmpUriMedia == null) //still null?
            return null;

        final Uri uriMedia = tmpUriMedia;

        final boolean showDeviceIds = mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE,ProofMode.PREF_OPTION_PHONE_DEFAULT);
        final boolean showLocation = mPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION,ProofMode.PREF_OPTION_LOCATION_DEFAULT);
        final boolean autoNotarize = mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT);
        final boolean showMobileNetwork = mPrefs.getBoolean(ProofMode.PREF_OPTION_NETWORK,ProofMode.PREF_OPTION_NETWORK_DEFAULT);

        final String mediaHash;
        try {
            mediaHash = HashUtils.getSHA256FromFileContent(context.getContentResolver().openInputStream(uriMedia));
        } catch (FileNotFoundException e) {
            Timber.e(e, "unable to open inputstream for hashing: %s", uriMedia);
            return null;
        }
        catch (IllegalStateException ise) {
            Timber.e(ise, "unable to open inputstream for hashing: %s", uriMedia);
            return null;
        }
         catch (SecurityException e) {
            Timber.e(e,"security exception accessing URI: %s",uriMedia);
            return null;
        }

        if (mediaHash != null) {

            try {
                if (proofExists(context,uriMedia,mediaHash))
                    return mediaHash;
            } catch (FileNotFoundException e) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s",mediaHash, uriMedia);

            //write immediate proof, w/o safety check result
            writeProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, null, false, false, -1, null);

            if (autoNotarize) {

                if (isOnline(context)) {
                    //if we can do safetycheck, then add that in as well
                    new SafetyNetCheck().sendSafetyNetRequest(context, mediaHash, new OnSuccessListener<SafetyNetApi.AttestationResponse>() {
                        @Override
                        public void onSuccess(SafetyNetApi.AttestationResponse response) {
                            // Indicates communication with the service was successful.
                            // Use response.getJwsResult() to get the result data.

                            String resultString = response.getJwsResult();
                            SafetyNetResponse resp = parseJsonWebSignature(resultString);

                            long timestamp = resp.getTimestampMs();
                            boolean isBasicIntegrity = resp.isBasicIntegrity();
                            boolean isCtsMatch = resp.isCtsProfileMatch();

                            Timber.d("Success! SafetyNet result: isBasicIntegrity: " + isBasicIntegrity + " isCts:" + isCtsMatch);
                            writeProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, resultString, isBasicIntegrity, isCtsMatch, timestamp, null);


                        }
                    }, new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            // An error occurred while communicating with the service.
                            Timber.d(e,"SafetyNet check failed");
                        }
                    });


                    final NotarizationProvider nProvider = new OpenTimestampsNotarizationProvider();
                    try {
                        nProvider.notarize("ProofMode Media Hash: " + mediaHash, context.getContentResolver().openInputStream(uriMedia), new NotarizationListener() {
                            @Override
                            public void notarizationSuccessful(String timestamp) {

                                Timber.d("Got OpenTimestamps success response timestamp: %s", timestamp);
                                writeProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, null, false, false, -1, "OpenTimestamps: " + timestamp);
                            }

                            @Override
                            public void notarizationFailed(int errCode, String message) {

                                Timber.d("Got OpenTimestamps error response: %s", message);
                                writeProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, null, false, false, -1, "OpenTimestamps Error: " + message);

                            }
                        });
                    } catch (FileNotFoundException e) {
                        Timber.e(e);
                    }
                }

            }

            return mediaHash;
        }
        else
        {
            Timber.d("Unable to access media files, no proof generated");

        }

        return null;
    }

    private boolean proofExists (Context context, Uri mediaUri, String hash) throws FileNotFoundException {
        boolean result = false;

        if (hash != null) {


            File fileFolder = MediaWatcher.getHashStorageDir(context,hash);

            if (fileFolder != null ) {
                File fileMediaProof = new File(fileFolder, hash + PROOF_FILE_TAG);


                if (fileMediaProof.exists()) {
                    Timber.d("Proof EXISTS for URI %s and hash %s", mediaUri, hash);

                    result = true;
                } else {
                    //generate now?
                    result = false;
                    Timber.d("Proof DOES NOT EXIST for URI %s and hash %s", mediaUri, hash);


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

    private SafetyNetResponse parseJsonWebSignature(String jwsResult) {
        if (jwsResult == null) {
            return null;
        }
        //the JWT (JSON WEB TOKEN) is just a 3 base64 encoded parts concatenated by a . character
        final String[] jwtParts = jwsResult.split("\\.");

        if (jwtParts.length == 3) {
            //we're only really interested in the body/payload
            String decodedPayload = new String(Base64.decode(jwtParts[1], Base64.DEFAULT));

            return SafetyNetResponse.parse(decodedPayload);
        } else {
            return null;
        }
    }

    private void writeProof (Context context, Uri uriMedia, String hash, boolean showDeviceIds, boolean showLocation, boolean showMobileNetwork, String safetyCheckResult, boolean isBasicIntegrity, boolean isCtsMatch, long notarizeTimestamp, String notes)
    {

      //  File fileMedia = new File(mediaPath);
        File fileFolder = getHashStorageDir(context,hash);

        if (fileFolder != null) {

            File fileMediaSig = new File(fileFolder, hash + OPENPGP_FILE_TAG);
            File fileMediaProof = new File(fileFolder, hash + PROOF_FILE_TAG);
            File fileMediaProofSig = new File(fileFolder, hash + PROOF_FILE_TAG + OPENPGP_FILE_TAG);

            try {

                //add data to proof csv and sign again
                boolean writeHeaders = !fileMediaProof.exists();
                String buildProof = buildProof(context, uriMedia, writeHeaders, showDeviceIds, showLocation, showMobileNetwork, safetyCheckResult, isBasicIntegrity, isCtsMatch, notarizeTimestamp, notes);
                writeTextToFile(context, fileMediaProof, buildProof);

                if (fileMediaProof.exists()) {
                    //sign the proof file again
                    PgpUtils.getInstance(context).createDetachedSignature(fileMediaProof, fileMediaProofSig, PgpUtils.DEFAULT_PASSWORD);
                }

                //sign the media file
               if (!fileMediaSig.exists())
                  PgpUtils.getInstance(context).createDetachedSignature(context.getContentResolver().openInputStream(uriMedia), new FileOutputStream(fileMediaSig), PgpUtils.DEFAULT_PASSWORD);

                Timber.d("Proof written/updated for uri %s and hash %s", uriMedia, hash);

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

    private String buildProof (Context context, Uri uriMedia, boolean writeHeaders, boolean showDeviceIds, boolean showLocation, boolean showMobileNetwork, String safetyCheckResult, boolean isBasicIntegrity, boolean isCtsMatch, long notarizeTimestamp, String notes)
    {
        String mediaPath = null;
        String[] projection = { MediaStore.Images.Media.DATA };

        Cursor cursor = context.getContentResolver().query(uriMedia,      projection,null, null, null);

        if (cursor != null) {
            if (cursor.getCount() > 0) {

                cursor.moveToFirst();
                int colIdx = cursor.getColumnIndex(projection[0]);
                if (colIdx > -1)
                    mediaPath = cursor.getString(colIdx);
            }

            cursor.close();
        }

        String hash = null;
        try {
            hash = getSHA256FromFileContent(context.getContentResolver().openInputStream(uriMedia));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.FULL,DateFormat.FULL);

        HashMap<String, String> hmProof = new HashMap<>();

        if (mediaPath != null)
            hmProof.put("File Path",mediaPath);
        else
            hmProof.put("File Path",uriMedia.toString());

        hmProof.put("File Hash SHA256",hash);

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
                    hmProof.put("Location.Latitude", "0");
                    hmProof.put("Location.Longitude", "0");
                    hmProof.put("Location.Provider", "none");
                    hmProof.put("Location.Accuracy", "0");
                    hmProof.put("Location.Altitude", "0");
                    hmProof.put("Location.Bearing", "0");
                    hmProof.put("Location.Speed", "0");
                    hmProof.put("Location.Time", "0");
                }

            }

            if (showMobileNetwork)
                hmProof.put("CellInfo", DeviceInfo.getCellInfo(context));
            else
                hmProof.put("CellInfo", "");

        }
        else
        {
            hmProof.put("Location.Latitude", "0");
            hmProof.put("Location.Longitude", "0");
            hmProof.put("Location.Provider", "none");
            hmProof.put("Location.Accuracy", "0");
            hmProof.put("Location.Altitude", "0");
            hmProof.put("Location.Bearing", "0");
            hmProof.put("Location.Speed", "0");
            hmProof.put("Location.Time", "0");
        }



        if (!TextUtils.isEmpty(safetyCheckResult)) {
            hmProof.put("SafetyCheck", safetyCheckResult);
            hmProof.put("SafetyCheckBasicIntegrity", isBasicIntegrity+"");
            hmProof.put("SafetyCheckCtsMatch", isCtsMatch+"");
            hmProof.put("SafetyCheckTimestamp", df.format(new Date(notarizeTimestamp)));
        }
        else
        {
            hmProof.put("SafetyCheck", "");
            hmProof.put("SafetyCheckBasicIntegrity", "");
            hmProof.put("SafetyCheckCtsMatch", "");
            hmProof.put("SafetyCheckTimestamp", "");
        }

        if (!TextUtils.isEmpty(notes))
            hmProof.put("Notes",notes);
        else
            hmProof.put("Notes","");


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

        sb.append("\n");

        return sb.toString();

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
}
