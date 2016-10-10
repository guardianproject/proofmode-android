package org.witness.proofmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
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

    private static PGPSecretKey pgpSec = null;

    private static String keyId = "noone@proofmode.witness.org";
    private static String password = "password";

    public MediaWatcher() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {

        Uri uriMedia = (Uri)intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uriMedia == null)
            uriMedia = intent.getData();

        Cursor cursor = context.getContentResolver().query(uriMedia,      null,null, null, null);
        cursor.moveToFirst();
        String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));
        cursor.close();

        File fileMediaProof = new File(mediaPath + ".proof.txt");
        writeTextToFile(fileMediaProof,buildProof(context,mediaPath));

        if (pgpSec == null)
        {
            initCrypto(context);
        }

        try {

            //sign the media file
            DetachedSignatureProcessor.createSignature(pgpSec, new FileInputStream(new File(mediaPath)), new FileOutputStream(new File(mediaPath + ".asc")), password.toCharArray(), true);

            //sign the proof file
            DetachedSignatureProcessor.createSignature(pgpSec, new FileInputStream(fileMediaProof), new FileOutputStream(new File(mediaPath + ".proof.txt.asc")), password.toCharArray(), true);
        }
        catch (Exception e)
        {
            Log.e("MediaWatcher","Error signing media or proof",e);
        }
    }

    private static synchronized void initCrypto (Context context)
    {
        if (pgpSec == null) {
            try {
                File fileSecKeyRing = new File(context.getFilesDir(),"pkr.asc");
                PGPSecretKeyRing skr = null;

                if (fileSecKeyRing.exists())
                {
                    ArmoredInputStream sin = new ArmoredInputStream(new FileInputStream(fileSecKeyRing));
                    skr = new PGPSecretKeyRing(sin,new BcKeyFingerprintCalculator());

                }
                else {
                    final PGPKeyRingGenerator krgen = PgpUtils.generateKeyRingGenerator(keyId, password.toCharArray());
                    skr = krgen.generateSecretKeyRing();
                    String pubKey = PgpUtils.genPGPPublicKey(krgen);
                    postKey(pubKey);

                    ArmoredOutputStream sout = new ArmoredOutputStream((new FileOutputStream(fileSecKeyRing)));
                    skr.encode(sout);
                    sout.close();
                }

                pgpSec = skr.getSecretKey();


            } catch (PGPException pgpe) {
                pgpe.printStackTrace();
            } catch (Exception pgpe) {
                pgpe.printStackTrace();

            }
        }
    }

    private static void postKey (final String pubKey)
    {
        new Thread () {

            public void run() {

                try {
                    HashMap<String,String> hmParams = new HashMap<String,String>();
                    hmParams.put("keytext",pubKey);
                    String queryString = createQueryStringForParameters(hmParams);

                    URL url = new URL("https://pgp.mit.edu/pks/add");
                    HttpURLConnection client = null;
                    client = (HttpURLConnection) url.openConnection();
                    client.setRequestMethod("POST");
                    client.setFixedLengthStreamingMode(queryString.getBytes().length);
                    client.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded");
                    client.setDoOutput(true);
                    client.setDoInput(true);
                    client.setReadTimeout(10000);
                    client.setConnectTimeout(15000);

                    PrintWriter out = new PrintWriter(client.getOutputStream());
                    out.print(queryString);
                    out.close();


                    // handle issues
                    int statusCode = client.getResponseCode();
                    if (statusCode != HttpURLConnection.HTTP_OK) {
                        // throw some exception
                        Log.w("PGP","key did not upload: " + statusCode);
                    }


                    client.disconnect();
                }
                catch (IOException ioe)
                {
                    ioe.printStackTrace();
                }
            }
        }.start();;
    }

    private static final char PARAMETER_DELIMITER = '&';
    private static final char PARAMETER_EQUALS_CHAR = '=';
    public static String createQueryStringForParameters(Map<String, String> parameters) {
        StringBuilder parametersAsQueryString = new StringBuilder();
        if (parameters != null) {
            boolean firstParameter = true;

            for (String parameterName : parameters.keySet()) {
                if (!firstParameter) {
                    parametersAsQueryString.append(PARAMETER_DELIMITER);
                }

                parametersAsQueryString.append(parameterName)
                        .append(PARAMETER_EQUALS_CHAR)
                        .append(URLEncoder.encode(
                                parameters.get(parameterName)));

                firstParameter = false;
            }
        }
        return parametersAsQueryString.toString();
    }





    private String buildProof (Context context, String mediaPath)
    {
        File fileMedia = new File (mediaPath);
        String hash = getSHA1FromFileContent(mediaPath);

        HashMap<String, String> hmProof = new HashMap<String, String>();

        hmProof.put("File",mediaPath);
        hmProof.put("SHA1",hash);
        hmProof.put("Modified",fileMedia.lastModified()+"");
        hmProof.put("CurrentDateTime0GMT",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_CURRENT_DATE_TIME_ZERO_GMT));

        hmProof.put("DeviceID",DeviceInfo.getDeviceId(context));

        hmProof.put("Wifi MAC",DeviceInfo.getWifiMacAddr());
        hmProof.put("IPV4",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV4));

        hmProof.put("DataType",DeviceInfo.getDataType(context));
        hmProof.put("Network",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_NETWORK));

        hmProof.put("NetworkType",DeviceInfo.getNetworkType(context));
        hmProof.put("Hardware",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_HARDWARE_MODEL));
        hmProof.put("Manufacturer",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_MANUFACTURE));

        hmProof.put("Language",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LANGUAGE));
        hmProof.put("Locale",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LOCALE));

        GPSTracker gpsTracker = new GPSTracker(context);
        if (gpsTracker.canGetLocation())
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

    private static void writeTextToFile (File fileOut, String text)
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

    private static String getSHA1FromFileContent(String filename)
    {

        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
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
