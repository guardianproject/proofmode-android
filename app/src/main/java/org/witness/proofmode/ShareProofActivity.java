package org.witness.proofmode;

import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.PgpUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;

public class ShareProofActivity extends AppCompatActivity {

    private final static String PROOF_FILE_TAG = ".proof.csv";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        CharSequence items[] = {"Share Proof Only","Share Proof with Media","Notarize Only"};

        new AlertDialog.Builder(this).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {

                switch (i)
                {
                    case 0:

                        shareProof (false, true);

                        break;
                    case 1:

                        shareProof (true, true);

                        break;
                    case 2:

                        shareProof (false, false);

                        break;
                }
            }
        }).show();
    }

    private void shareProof (boolean shareMedia, boolean shareProof)
    {
        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        ArrayList<Uri> shareUris = new ArrayList<Uri>();
        StringBuffer shareText = new StringBuffer ();

        if (Intent.ACTION_SEND_MULTIPLE.equals(action))
        {
            ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);

            File fileBatchProof = null;

            PrintWriter fBatchProofOut = null;

            try
            {
                File fileParent =new File(Environment.getExternalStorageDirectory(),"proofmode");
                fileParent.mkdirs();
                fileBatchProof = new File(fileParent,new Date().getTime() + "batchproof.csv");
                fBatchProofOut = new PrintWriter(new FileWriter(fileBatchProof,  true));
            }
            catch (IOException ioe) {}


            for (Uri mediaUri : mediaUris)
            {
                processUri (mediaUri, shareUris, shareText, fBatchProofOut, shareMedia);
            }


            if (fBatchProofOut != null && fileBatchProof != null) {
                fBatchProofOut.flush();
                fBatchProofOut.close();
                shareUris.add(Uri.fromFile(fileBatchProof)); // Add your image URIs here
            }

        }

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            Uri mediaUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null)
                processUri (mediaUri, shareUris, shareText, null, shareMedia);


        }

        Intent shareIntent = null;

        if (shareUris.size() > 0) {

            if (!shareProof)
            {
                shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_TEXT, shareText.toString());
                shareIntent.setType("*/*");
                startActivity(Intent.createChooser(shareIntent, "Share notarization to..."));
            }
            else {

                shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);

                shareIntent.putExtra(Intent.EXTRA_TEXT, shareText.toString());
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);
                shareIntent.setType("*/*");
                startActivity(Intent.createChooser(shareIntent, "Share proof to..."));
            }
        }


        finish();
    }

    private void processUri (Uri mediaUri, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia)
    {
        Cursor cursor = getContentResolver().query(mediaUri,      null,null, null, null);

        if (cursor.getCount() > 0) {

            cursor.moveToFirst();
            String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));

            if (mediaPath != null) {
                //check proof metadata against original image

                String baseFolder = "proofmode";

                File fileMedia = new File(mediaPath);
                File fileMediaSig = new File(mediaPath + ".asc");
                File fileMediaProof = new File(mediaPath + PROOF_FILE_TAG);
                File fileMediaProofSig = new File(fileMediaProof.getAbsolutePath() + ".asc");

                //if not there try alternate locations
                if (!fileMediaSig.exists())
                {
                    fileMediaSig = new File(Environment.getExternalStorageDirectory(),baseFolder + mediaPath + ".asc");
                    fileMediaProof = new File(Environment.getExternalStorageDirectory(),baseFolder + mediaPath + PROOF_FILE_TAG);
                    fileMediaProofSig = new File(fileMediaProof.getAbsolutePath() + ".asc");

                    if (!fileMediaSig.exists())
                    {
                        fileMediaSig = new File(getExternalFilesDir(null),mediaPath + ".asc");
                        fileMediaProof = new File(getExternalFilesDir(null),mediaPath + PROOF_FILE_TAG);
                        fileMediaProofSig = new File(fileMediaProof.getAbsolutePath() + ".asc");
                    }

                }

                if (fileMediaSig.exists() && fileMediaProof.exists() && fileMediaProofSig.exists()) {

                    String hash = HashUtils.getSHA256FromFileContent(mediaPath);
                    String fingerprint = PgpUtils.getInstance(this).getPublicKeyFingerprint();

                    sb.append(fileMedia.getName()).append(' ');
                    sb.append(" was last modified at ").append(new Date(fileMedia.lastModified()).toGMTString());
                    sb.append(" and has a SHA-256 hash of ").append(hash);
                    sb.append("\n\n");
                    sb.append("This proof is signed by PGP key 0x" + fingerprint);

                    if (shareMedia) {
                        shareUris.add(Uri.fromFile(new File(mediaPath))); // Add your image URIs here
                        shareUris.add(Uri.fromFile(fileMediaSig)); // Add your image URIs here
                    }
                    shareUris.add(Uri.fromFile(fileMediaProof));
                    shareUris.add(Uri.fromFile(fileMediaProofSig));

                    if (fBatchProofOut != null)
                    {
                        try {
                            BufferedReader br = new BufferedReader(new FileReader(fileMediaProof));
                            br.readLine();//skip header
                            String csvLine = br.readLine();
                           // Log.i("ShareProof","batching csv line: " + csvLine);
                            fBatchProofOut.println(csvLine);
                            br.close();
                        }
                        catch (IOException ioe)
                        {}
                    }

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
