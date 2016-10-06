package org.witness.proofmode;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import java.io.File;
import java.util.ArrayList;

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
            if (type.startsWith("image/")) {
                Uri imageUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (imageUri != null) {
                    // Update UI to reflect image being shared

                    Cursor cursor = getContentResolver().query(imageUri,      null,null, null, null);
                    cursor.moveToFirst();
                    String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));
                    cursor.close();

                    if (mediaPath != null)
                    {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);

                        ArrayList<Uri> imageUris = new ArrayList<Uri>();
                        imageUris.add(imageUri); // Add your image URIs here
                        imageUris.add(Uri.fromFile(new File(mediaPath + ".proof.txt")));

                        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris);
                        shareIntent.setType("*/*");
                        startActivity(Intent.createChooser(shareIntent, "Share proof to.."));

                        finish();
                    }


                }
            }
        }
    }
}
