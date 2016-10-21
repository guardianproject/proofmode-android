package org.witness.proofmode;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.Toast;

import org.witness.proofmode.crypto.HashUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;

public class ShareProofActivity extends AppCompatActivity {

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

        Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        ArrayList<Uri> shareUris = new ArrayList<Uri>();
        StringBuffer shareText = new StringBuffer ();

        if (Intent.ACTION_SEND_MULTIPLE.equals(action))
        {
            ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);

            for (Uri mediaUri : mediaUris)
            {
                processUri (mediaUri, shareIntent, shareUris, shareText);
            }
        }

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            Uri mediaUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null)
                processUri (mediaUri, shareIntent, shareUris, shareText);


        }

        if (shareUris.size() > 0) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareText.toString());
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);
            shareIntent.setType("*/*");
            startActivity(Intent.createChooser(shareIntent, "Share proof to..."));
        }


        finish();
    }

    private void processUri (Uri mediaUri, Intent shareIntent, ArrayList<Uri> shareUris, StringBuffer sb)
    {
        Cursor cursor = getContentResolver().query(mediaUri,      null,null, null, null);

        if (cursor.getCount() > 0) {

            cursor.moveToFirst();
            String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));

            if (mediaPath != null) {
                //check proof metadata against original image


                File fileMedia = new File(mediaPath);
                File fileMediaSig = new File(mediaPath + ".asc");
                File fileMediaProof = new File(mediaPath + ".proof.txt");
                File fileMediaProofSig = new File(mediaPath + ".proof.txt.asc");

                if (fileMediaSig.exists() && fileMediaProof.exists() && fileMediaProofSig.exists()) {
                    String hash = HashUtils.getSHA1FromFileContent(mediaPath);
                    sb.append(fileMedia.getName()).append(' ');
                    sb.append(" was last modifed at ").append(new Date(fileMedia.lastModified()).toGMTString());
                    sb.append(" and has a SHA1 hash of ").append(hash);
                    sb.append("\n\n");


                    shareUris.add(Uri.fromFile(new File(mediaPath))); // Add your image URIs here
                    shareUris.add(Uri.fromFile(fileMediaSig)); // Add your image URIs here
                    shareUris.add(Uri.fromFile(fileMediaProof));
                    shareUris.add(Uri.fromFile(fileMediaProofSig));


                }
                else
                {
                    Toast.makeText(this, "ERROR: The proof does not exist or has been modified",Toast.LENGTH_LONG).show();
                }


            }
        }

        cursor.close();

    }
}
