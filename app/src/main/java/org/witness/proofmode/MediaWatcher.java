package org.witness.proofmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.widget.Toast;

import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyRingGenerator;
import org.spongycastle.openpgp.PGPSecretKey;
import org.spongycastle.openpgp.PGPSecretKeyRing;
import org.witness.proofmode.crypto.DetachedSignatureProcessor;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.util.DeviceInfo;
import org.witness.proofmode.util.GPSTracker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.HashMap;

public class MediaWatcher extends BroadcastReceiver {

    PGPSecretKey pgpSec = null;

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

        File fileMediaProofAsc = new File(mediaPath + ".proof.txt.asc");

        if (pgpSec == null)
        {
            initCrypto();
        }

        try {
            DetachedSignatureProcessor.createSignature(pgpSec, new FileInputStream(fileMediaProof), new FileOutputStream(fileMediaProofAsc), "password".toCharArray(), true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private synchronized void initCrypto ()
    {
        if (pgpSec == null) {
            try {
                final PGPKeyRingGenerator krgen = PgpUtils.generateKeyRingGenerator("password".toCharArray());
                PGPSecretKeyRing skr = krgen.generateSecretKeyRing();
                pgpSec = skr.getSecretKey();

                //            String pgpSecretKey = PgpUtils.genPGPPrivKey(krgen);

            } catch (PGPException pgpe) {
                pgpe.printStackTrace();
            } catch (Exception pgpe) {
                pgpe.printStackTrace();

            }
        }
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
