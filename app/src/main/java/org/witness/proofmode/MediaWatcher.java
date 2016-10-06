package org.witness.proofmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.widget.Toast;

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

public class MediaWatcher extends BroadcastReceiver {

    public MediaWatcher() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {

        Cursor cursor = context.getContentResolver().query(intent.getData(),      null,null, null, null);
        cursor.moveToFirst();
        String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));
        cursor.close();
        Toast.makeText(context, "Generating proof: " + mediaPath, Toast.LENGTH_SHORT).show();

        writeTextToFile(new File(mediaPath + ".proof.txt"),buildProof(context,mediaPath));

    }

    private String buildProof (Context context, String mediaPath)
    {
        File fileMedia = new File (mediaPath);
        String hash = getSHA1FromFileContent(mediaPath);

        StringBuffer sb = new StringBuffer();
        sb.append("File: ").append(mediaPath).append("\n");
        sb.append("SHA1: ").append(hash).append("\n");
        sb.append("Modified: ").append(fileMedia.lastModified()).append("\n");
        sb.append("CurrentDateTime0GMT: ").append(DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_CURRENT_DATE_TIME_ZERO_GMT)).append("\n");

        sb.append("DeviceID: ").append(DeviceInfo.getDeviceId(context)).append("\n");

        sb.append("MAC: ").append(DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_MAC_ADDRESS)).append("\n");
        sb.append("IPV4: ").append(DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV4)).append("\n");

        sb.append("DataType: ").append(DeviceInfo.getDataType(context)).append("\n");
        sb.append("Network: ").append(DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_NETWORK)).append("\n");

        sb.append("NetworkType: ").append(DeviceInfo.getNetworkType(context)).append("\n");
        sb.append("Hardware: ").append(DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_HARDWARE_MODEL)).append("\n");
        sb.append("Manufacturer: ").append(DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_MANUFACTURE)).append("\n");

        sb.append("Language: ").append(DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LANGUAGE)).append("\n");
        sb.append("Locale: ").append(DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LOCALE)).append("\n");

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
                sb.append("Location.LatLon: " + loc.getLatitude() + "," + loc.getLongitude()).append("\n");
                sb.append("Location.Provider: " + loc.getProvider()).append("\n");
                sb.append("Location.Accuracy: " + loc.getAccuracy()).append("\n");
                sb.append("Location.Altitude: " + loc.getAltitude()).append("\n");
                sb.append("Location.Bearing: " + loc.getBearing()).append("\n");
                sb.append("Location.Speed: " + loc.getSpeed()).append("\n");
                sb.append("Location.Time: " + loc.getTime()).append("\n");
            }
        }


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
