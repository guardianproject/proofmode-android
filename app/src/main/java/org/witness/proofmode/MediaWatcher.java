package org.witness.proofmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.widget.Toast;

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
        String hash = getSHA1FromFileContent(mediaPath);

        Toast.makeText(context, "New media: " + mediaPath + " and hash=" + hash, Toast.LENGTH_SHORT).show();

        writeTextToFile(new File(mediaPath + ".sha1"),hash);
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
