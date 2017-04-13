package org.witness.proofmode.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.safetynet.SafetyNetApi;

import org.witness.proofmode.ProofModeApp;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.util.DeviceInfo;
import org.witness.proofmode.util.GPSTracker;
import org.witness.proofmode.util.SafetyNetCheck;
import org.witness.proofmode.util.SafetyNetResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.util.HashMap;

public class MediaWatcher extends BroadcastReceiver {

    private final static String PROOF_FILE_TAG = ".proof.csv";
    private final static String OPENPGP_FILE_TAG = ".asc";
    private final static String PROOF_BASE_FOLDER = "proofmode";

    private static boolean mStorageMounted = false;

    public MediaWatcher() {
    }

    @Override
    public void onReceive(final Context context, Intent intent) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        boolean doProof = prefs.getBoolean("doProof", true);
        boolean autoNotarize = prefs.getBoolean("autoNotarize", true);

        if (intent.getAction() != null) {
            if (intent.getAction().equals(Intent.ACTION_UMS_CONNECTED)) {
                mStorageMounted = true;
            } else if (intent.getAction().equals(Intent.ACTION_UMS_DISCONNECTED)) {
                mStorageMounted = false;
            }
        }

        if (doProof) {

            if (!isExternalStorageWritable()) {
                Toast.makeText(context, "WARNING: ProofMode enabled, but there is no external storage available!", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uriMedia = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uriMedia == null)
                uriMedia = intent.getData();

            String mediaPathTmp = null;

            Cursor cursor = context.getContentResolver().query(uriMedia, null, null, null, null);
            if (cursor != null) {
                cursor.moveToFirst();
                mediaPathTmp = cursor.getString(cursor.getColumnIndex("_data"));
                cursor.close();
            } else {
                mediaPathTmp = uriMedia.getPath();
            }

            final String mediaPath = mediaPathTmp;

            final boolean showDeviceIds = prefs.getBoolean("trackDeviceId",true);;
            final boolean showLocation = prefs.getBoolean("trackLocation",true);;;

            final String mediaHash = HashUtils.getSHA256FromFileContent(mediaPath);

            if (mediaHash != null) {
                //write immediate proof, w/o safety check result
                writeProof(context, mediaPath, mediaHash, showDeviceIds, showLocation, null, false, false, -1);

                if (autoNotarize) {

                    //if we can do safetycheck, then add that in as well
                    new SafetyNetCheck().sendSafetyNetRequest(mediaHash, new ResultCallback<SafetyNetApi.AttestationResult>() {

                        @Override
                        public void onResult(SafetyNetApi.AttestationResult result) {
                            Status status = result.getStatus();
                            if (status.isSuccess()) {
                                String resultString = result.getJwsResult();
                                SafetyNetResponse resp = parseJsonWebSignature(resultString);

                                long timestamp = resp.getTimestampMs();
                                boolean isBasicIntegrity = resp.isBasicIntegrity();
                                boolean isCtsMatch = resp.isCtsProfileMatch();

                                Log.d(ProofModeApp.TAG, "Success! SafetyNet result: isBasicIntegrity: " + isBasicIntegrity + " isCts:" + isCtsMatch);
                                writeProof(context, mediaPath, mediaHash, showDeviceIds, showLocation, resultString, isBasicIntegrity, isCtsMatch, timestamp);


                            } else {
                                // An error occurred while communicating with the service.
                                Log.d(ProofModeApp.TAG, "ERROR! " + status.getStatusCode() + " " + status
                                        .getStatusMessage());

                            }
                        }
                    });
                }
            }
            else
            {
                Toast.makeText(context,"ProofMode Alert: Unable to access media files",Toast.LENGTH_SHORT).show();
            }

        }

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

    private void writeProof (Context context, String mediaPath, String hash, boolean showDeviceIds, boolean showLocation, String safetyCheckResult, boolean isBasicIntegrity, boolean isCtsMatch, long notarizeTimestamp)
    {

        File fileMedia = new File(mediaPath);
        File fileFolder = getHashStorageDir(hash);

        File fileMediaSig = new File(fileFolder, fileMedia.getName() + OPENPGP_FILE_TAG);
        File fileMediaProof = new File(fileFolder, fileMedia.getName() + PROOF_FILE_TAG);
        File fileMediaProofSig = new File(fileFolder, fileMedia.getName() + PROOF_FILE_TAG + OPENPGP_FILE_TAG);

        try {

            //sign the media file
            if (!fileMediaSig.exists())
                PgpUtils.getInstance(context).createDetachedSignature(new File(mediaPath), fileMediaSig);

            //add data to proof csv and sign again
            boolean writeHeaders = !fileMediaProof.exists();
            writeTextToFile(context, fileMediaProof, buildProof(context, mediaPath, writeHeaders, showDeviceIds, showLocation, safetyCheckResult, isBasicIntegrity, isCtsMatch, notarizeTimestamp));

            //sign the proof file again
            PgpUtils.getInstance(context).createDetachedSignature(fileMediaProof, fileMediaProofSig);

        } catch (Exception e) {
            Log.e("MediaWatcher", "Error signing media or proof", e);
            Toast.makeText(context, "Unable to save proof: " + e.getLocalizedMessage(),Toast.LENGTH_SHORT).show();

        }


    }

    public static File getHashStorageDir(String hash) {

        // Get the directory for the user's public pictures directory.
        File file = null;

        if (android.os.Build.VERSION.SDK_INT >= 19) {
            file = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), PROOF_BASE_FOLDER);

        }
        else
        {
            file = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), PROOF_BASE_FOLDER);
        }

        file = new File(file, hash);
        file.mkdirs();

        return file;
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    private String buildProof (Context context, String mediaPath, boolean writeHeaders, boolean showDeviceIds, boolean showLocation, String safetyCheckResult, boolean isBasicIntegrity, boolean isCtsMatch, long notarizeTimestamp)
    {
        File fileMedia = new File (mediaPath);
        String hash = getSHA256FromFileContent(mediaPath);

        HashMap<String, String> hmProof = new HashMap<>();

        hmProof.put("File",mediaPath);
        hmProof.put("SHA256",hash);
        hmProof.put("Modified",fileMedia.lastModified()+"");
        hmProof.put("CurrentDateTime0GMT",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_CURRENT_DATE_TIME_ZERO_GMT));

        if (showDeviceIds) {
            hmProof.put("DeviceID", DeviceInfo.getDeviceId(context));
            hmProof.put("Wifi MAC", DeviceInfo.getWifiMacAddr());
        }

        hmProof.put("IPV4",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV4));

        hmProof.put("DataType",DeviceInfo.getDataType(context));
        hmProof.put("Network",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_NETWORK));

        hmProof.put("NetworkType",DeviceInfo.getNetworkType(context));
        hmProof.put("Hardware",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_HARDWARE_MODEL));
        hmProof.put("Manufacturer",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_MANUFACTURE));
        hmProof.put("ScreenSize",DeviceInfo.getDeviceInch(context));

        hmProof.put("Language",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LANGUAGE));
        hmProof.put("Locale",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LOCALE));

        GPSTracker gpsTracker = new GPSTracker(context);
        if (showLocation
                && gpsTracker.canGetLocation())
        {
            Location loc = gpsTracker.getLocation();
            int waitIdx = 0;
            while (loc == null && waitIdx < 3)
            {
                waitIdx++;
                try { Thread.sleep (500); }
                catch (Exception e){}
                loc = gpsTracker.getLocation();
            }

            if (loc != null) {
                hmProof.put("Location.Latitude",loc.getLatitude()+"");
                hmProof.put("Location.Longitude",loc.getLongitude()+"");
                hmProof.put("Location.Provider",loc.getProvider());
                hmProof.put("Location.Accuracy",loc.getAccuracy()+"");
                hmProof.put("Location.Altitude",loc.getAltitude()+"");
                hmProof.put("Location.Bearing",loc.getBearing()+"");
                hmProof.put("Location.Speed",loc.getSpeed()+"");
                hmProof.put("Location.Time",loc.getTime()+"");
            }


        }

        if (!TextUtils.isEmpty(safetyCheckResult)) {
            hmProof.put("SafetyCheck", safetyCheckResult);
            hmProof.put("SafetyCheckBasicIntegrity", isBasicIntegrity+"");
            hmProof.put("SafetyCheckCtsMatch", isCtsMatch+"");
            hmProof.put("SafetyCheckTimestamp", notarizeTimestamp+"");
        }
        else
        {
            hmProof.put("SafetyCheck", "");
            hmProof.put("SafetyCheckBasicIntegrity", "");
            hmProof.put("SafetyCheckCtsMatch", "");
            hmProof.put("SafetyCheckTimestamp", "");
        }

        StringBuffer sb = new StringBuffer();

        if (writeHeaders) {
            for (String key : hmProof.keySet()) {
                sb.append(key).append(",");
            }

            sb.append("\n");
        }

        for (String key : hmProof.keySet())
        {
            sb.append(hmProof.get(key)).append(",");
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

    private static String asHex(byte[] arrayBytes) {
        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < arrayBytes.length; i++) {
            stringBuffer.append(Integer.toString((arrayBytes[i] & 0xff) + 0x100, 16)
                    .substring(1));
        }
        return stringBuffer.toString();
    }
}
