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
import android.util.Log;
import android.widget.Toast;

import org.spongycastle.bcpg.ArmoredInputStream;
import org.spongycastle.bcpg.ArmoredOutputStream;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyRingGenerator;
import org.spongycastle.openpgp.PGPSecretKey;
import org.spongycastle.openpgp.PGPSecretKeyRing;
import org.spongycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.witness.proofmode.crypto.DetachedSignatureProcessor;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.util.DeviceInfo;
import org.witness.proofmode.util.GPSTracker;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;

public class MediaWatcher extends BroadcastReceiver {

    private final static String PROOF_FILE_TAG = ".proof.csv";
    private final static String OPENPGP_FILE_TAG = ".asc";
    private final static String PROOF_BASE_FOLDER = "proofmode";

    public MediaWatcher() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        boolean doProof = prefs.getBoolean("doProof",true);

        if (doProof) {

            if (!isExternalStorageWritable())
            {
                Toast.makeText(context, "WARNING: ProofMode enabled, but there is no external storage available!",Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uriMedia = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uriMedia == null)
                uriMedia = intent.getData();

            String mediaPath = null;

            Cursor cursor = context.getContentResolver().query(uriMedia, null, null, null, null);
            if (cursor != null) {
                cursor.moveToFirst();
                mediaPath = cursor.getString(cursor.getColumnIndex("_data"));
                cursor.close();
            } else {
                mediaPath = uriMedia.getPath();
            }


            String hash = HashUtils.getSHA256FromFileContent(mediaPath);

            File fileMedia = new File(mediaPath);
            File fileFolder = getHashStorageDir(hash);

            File fileMediaSig = new File(fileFolder, fileMedia.getName() + OPENPGP_FILE_TAG);
            File fileMediaProof = new File(fileFolder, fileMedia.getName() + PROOF_FILE_TAG);
            File fileMediaProofSig = new File(fileFolder, fileMedia.getName() + PROOF_FILE_TAG + OPENPGP_FILE_TAG);

            boolean showDeviceIds = prefs.getBoolean("trackDeviceId",true);;
            boolean showLocation = prefs.getBoolean("trackLocation",true);;;

            if (!fileMediaProof.exists()) {

                try {

                    writeTextToFile(context, fileMediaProof, buildProof(context, mediaPath, showDeviceIds, showLocation));

                    //sign the media file
                    PgpUtils.getInstance(context).createDetachedSignature(new File(mediaPath), fileMediaSig);

                    //sign the proof file
                    PgpUtils.getInstance(context).createDetachedSignature(fileMediaProof, fileMediaProofSig);
                    
                } catch (Exception e) {
                    Log.e("MediaWatcher", "Error signing media or proof", e);
                    Toast.makeText(context, "Unable to save proof: " + e.getLocalizedMessage(),Toast.LENGTH_SHORT).show();

                }
            }
        }
    }

    public static File getHashStorageDir(String hash) {
        // Get the directory for the user's public pictures directory.
        File file = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), PROOF_BASE_FOLDER);
        file = new File(file, hash);

        if (!file.mkdirs()) {
            Log.e("ProofMode", "Directory not created");
        }
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

    private String buildProof (Context context, String mediaPath, boolean showDeviceIds, boolean showLocation)
    {
        File fileMedia = new File (mediaPath);
        String hash = getSHA256FromFileContent(mediaPath);

        HashMap<String, String> hmProof = new HashMap<String, String>();

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

        StringBuffer sb = new StringBuffer();

        for (String key : hmProof.keySet())
        {
            sb.append(key).append(",");
        }

        sb.append("\n");

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
            PrintStream ps = new PrintStream(new FileOutputStream(fileOut));
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
