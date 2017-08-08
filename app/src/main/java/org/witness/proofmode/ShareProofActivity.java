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
import org.witness.proofmode.service.MediaWatcher;

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
    private final static String OPENPGP_FILE_TAG = ".asc";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        CharSequence items[] = {getString(R.string.notarize_only), getString(R.string.share_proof_only),getString(R.string.share_proof_with_media),};

        new AlertDialog.Builder(this).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {

                switch (i)
                {
                    case 0:

                        shareProof (false, false);

                        break;
                    case 1:

                        shareProof (false, true);

                        break;
                    case 2:

                        shareProof (true, true);

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
                File fileFolder = MediaWatcher.getHashStorageDir("batch");
                fileBatchProof = new File(fileFolder,new Date().getTime() + "batchproof.csv");
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
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_notarization)));
            }
            else {

                shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);

                ArrayList<String> arrayListStrings = new ArrayList<String>();
                for (int i = 0; i < shareUris.size(); i++)
                    arrayListStrings.add(shareText.toString());

                shareIntent.putExtra(Intent.EXTRA_TEXT, arrayListStrings);
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);
                shareIntent.setType("*/*");
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_proof)));
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

                boolean success = shareProof(mediaPath, shareUris, sb, fBatchProofOut, shareMedia);

                if (!success)
                    success = shareProofClassic(mediaPath, shareUris, sb, fBatchProofOut, shareMedia);

                if (!success)
                {
                    Toast.makeText(this, R.string.proof_error_message,Toast.LENGTH_LONG).show();
                }
            }
        }

        cursor.close();

    }

    private boolean shareProof (String mediaPath, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia)
    {

        String hash = HashUtils.getSHA256FromFileContent(new File(mediaPath));

        if (hash != null) {
            File fileMedia = new File(mediaPath);
            File fileFolder = MediaWatcher.getHashStorageDir(hash);

            File fileMediaSig = new File(fileFolder, fileMedia.getName() + OPENPGP_FILE_TAG);
            File fileMediaProof = new File(fileFolder, fileMedia.getName() + PROOF_FILE_TAG);
            File fileMediaProofSig = new File(fileFolder, fileMedia.getName() + PROOF_FILE_TAG + OPENPGP_FILE_TAG);

            if (fileMediaSig.exists() && fileMediaProof.exists() && fileMediaProofSig.exists()) {
                generateProofOutput(fileMedia, fileMediaSig, fileMediaProof, fileMediaProofSig, hash, shareMedia, fBatchProofOut, shareUris, sb);
                return true;
            }
        }

        return false;

    }

    private boolean shareProofClassic (String mediaPath, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia)
    {

        String baseFolder = "proofmode";

        String hash = HashUtils.getSHA256FromFileContent(new File(mediaPath));

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

           generateProofOutput(fileMedia, fileMediaSig, fileMediaProof, fileMediaProofSig, hash, shareMedia, fBatchProofOut, shareUris, sb);

            return true;
        }


        return false;


    }

    private void generateProofOutput (File fileMedia, File fileMediaSig, File fileMediaProof, File fileMediaProofSig, String hash, boolean shareMedia, PrintWriter fBatchProofOut, ArrayList<Uri> shareUris, StringBuffer sb)
    {
        String fingerprint = PgpUtils.getInstance(this).getPublicKeyFingerprint();

        sb.append(fileMedia.getName()).append(' ');
        sb.append(getString(R.string.last_modified)).append(new Date(fileMedia.lastModified()).toGMTString());
        sb.append(getString(R.string.has_hash)).append(hash);
        sb.append("\n\n");
        sb.append(getString(R.string.proof_signed) + fingerprint);

        if (shareMedia) {
            shareUris.add(Uri.fromFile(fileMedia)); // Add your image URIs here
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
}
