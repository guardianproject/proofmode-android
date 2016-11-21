package org.witness.proofmode;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import org.witness.proofmode.crypto.HashUtils;

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

                    String hash = HashUtils.getSHA256FromFileContent(mediaPath);
                    File fileMedia = new File(mediaPath);

                    StringBuffer sb = new StringBuffer();

                    sb.append(fileMedia.getName()).append(' ');
                    sb.append(" was last modified at ").append(new Date(fileMedia.lastModified()).toGMTString());
                    sb.append(" and has a SHA-256 hash of ").append(hash);

                    notarizeIntent.putExtra(Intent.EXTRA_TEXT,sb.toString());
                    notarizeIntent.setType("text/plain");

                    startActivity(notarizeIntent);

                    finish();
                }


            }

        }
    }


}
