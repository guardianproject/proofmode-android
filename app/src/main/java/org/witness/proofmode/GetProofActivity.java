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

public class GetProofActivity extends AppCompatActivity {

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

                if (cursor.getCount() > 0) {

                    cursor.moveToFirst();
                    String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));

                    if (mediaPath != null) {
                        //check proof metadata against original image

                        Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);

                        File fileMedia = new File(mediaPath);
                        File fileMediaSig = new File(mediaPath + ".asc");
                        File fileMediaProof = new File(mediaPath + ".proof.txt");
                        File fileMediaProofSig = new File(mediaPath + ".proof.txt.asc");

                        if (fileMediaSig.exists() && fileMediaProof.exists() && fileMediaProofSig.exists()) {
                            String hash = HashUtils.getSHA1FromFileContent(mediaPath);
                            StringBuffer sb = new StringBuffer();
                            sb.append(fileMedia.getName()).append(' ');
                            sb.append(" was last modifed at ").append(new Date(fileMedia.lastModified()).toGMTString());
                            sb.append(" and has a SHA1 hash of ").append(hash);

                            shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());

                            ArrayList<Uri> imageUris = new ArrayList<Uri>();
                            imageUris.add(Uri.fromFile(new File(mediaPath))); // Add your image URIs here
                            imageUris.add(Uri.fromFile(fileMediaSig)); // Add your image URIs here
                            imageUris.add(Uri.fromFile(fileMediaProof));
                            imageUris.add(Uri.fromFile(fileMediaProofSig));

                            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris);
                            shareIntent.setType("*/*");
                            startActivity(Intent.createChooser(shareIntent, "Share proof to.."));
                        }
                        else
                        {
                            Toast.makeText(this, "ERROR: The proof does not exist or has been modified",Toast.LENGTH_LONG).show();
                        }


                    }
                }

                cursor.close();
                finish();


            }

        }
    }
}
