package org.witness.proofmode;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;

public class NotarizeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        checkIntent();
    }

    private void checkIntent ()
    {
        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();


        if (Intent.ACTION_SEND.equals(action) && type != null) {
            Uri imageUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (imageUri == null)
                imageUri = intent.getData();

            if (imageUri != null) {
                // Update UI to reflect image being shared

                Cursor cursor = getContentResolver().query(imageUri,      null,null, null, null);
                cursor.moveToFirst();
                String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));
                cursor.close();

                if (mediaPath != null)
                {
                    //check proof metadata against original image

                    Intent notarizeIntent = new Intent(Intent.ACTION_SEND);

                    String hash = getSHA1FromFileContent(mediaPath);
                    File fileMedia = new File(mediaPath);

                    StringBuffer sb = new StringBuffer();

                    sb.append(fileMedia.getName()).append(' ');
                    sb.append(" was last modifed at ").append(new Date(fileMedia.lastModified()).toGMTString());
                    sb.append(" and has a SHA1 hash of ").append(hash);

                    notarizeIntent.putExtra(Intent.EXTRA_TEXT,sb.toString());
                    notarizeIntent.setType("text/plain");

                    startActivity(notarizeIntent);

                    finish();
                }


            }

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
