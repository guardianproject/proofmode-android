package org.witness.proofmode;

import android.Manifest;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.notarization.TimeBeatNotarizationProvider;
import org.witness.proofmode.service.MediaListenerService;
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
import java.util.List;

import timber.log.Timber;

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

        askForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE,4);

        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        ArrayList<Uri> shareUris = new ArrayList<Uri>();
        StringBuffer shareText = new StringBuffer();

        boolean proofExists = false;

        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);

            for (Uri mediaUri : mediaUris)
            {
                proofExists = proofExists(mediaUri);

                if (!proofExists)
                    break;
            }

        } else if (Intent.ACTION_SEND.equals(action) && type != null) {

            Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null)
            {
                proofExists = proofExists(mediaUri);
            }
        }

        if (proofExists)
        {
            displaySharePrompt();
        }
        else
        {
            Toast.makeText(this, R.string.proof_error_message,Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void displaySharePrompt ()
    {
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
        }).setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                finish();
            }
        }).setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                finish();
            }
        }).show();

    }

    private boolean shareProof (final boolean shareMedia, final boolean shareProof) {

        Toast.makeText(this, R.string.packaging_proof,Toast.LENGTH_LONG).show();

        new Thread(new Runnable (){

            public void run ()
            {
                shareProofAsync(shareMedia, shareProof);

            }
        }).start();

        return true;
    }

    private boolean shareProofAsync (boolean shareMedia, boolean shareProof)
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

                if (fileFolder == null)
                    return false;

                fileBatchProof = new File(fileFolder,new Date().getTime() + "batchproof.csv");
                fBatchProofOut = new PrintWriter(new FileWriter(fileBatchProof,  true));
            }
            catch (IOException ioe) {}


            for (Uri mediaUri : mediaUris)
            {
                if (!processUri (mediaUri, shareUris, shareText, fBatchProofOut, shareMedia))
                    return false;
            }


            if (fBatchProofOut != null && fileBatchProof != null) {
                fBatchProofOut.flush();
                fBatchProofOut.close();
                shareUris.add(Uri.fromFile(fileBatchProof)); // Add your image URIs here
            }

        }
        else if (Intent.ACTION_SEND.equals(action) && type != null) {

            Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null)
                if (!processUri (mediaUri, shareUris, shareText, null, shareMedia))
                    return false;

        }

        if (shareUris.size() > 0) {

            if (!shareProof)
                shareAll(shareProof, shareText.toString(), shareUris);
            else
                shareFiltered(getString(R.string.select_app), shareText.toString(), shareUris);
        }

        finish();

        return true;
    }

    private boolean proofExists (Uri mediaUri)
    {
        boolean result = false;

        Cursor cursor = getContentResolver().query(mediaUri,      null,null, null, null);

        if (cursor.getCount() > 0) {

            cursor.moveToFirst();
            final String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));

            if (mediaPath != null) {

                String hash = HashUtils.getSHA256FromFileContent(new File(mediaPath));

                if (hash != null) {
                    File fileMedia = new File(mediaPath);
                    File fileFolder = MediaWatcher.getHashStorageDir(hash);

                    if (fileFolder != null ) {
                        File fileMediaSig = new File(fileFolder, fileMedia.getName() + OPENPGP_FILE_TAG);
                        File fileMediaProof = new File(fileFolder, fileMedia.getName() + PROOF_FILE_TAG);
                        File fileMediaProofSig = new File(fileFolder, fileMedia.getName() + PROOF_FILE_TAG + OPENPGP_FILE_TAG);

                        if (fileMediaSig.exists() && fileMediaProof.exists() && fileMediaProofSig.exists()) {
                            result = true;
                        } else {
                            //generate now?

                            new AsyncTask<Void, Void, String>() {
                                protected String doInBackground(Void... params) {
                                    Intent intent = new Intent();
                                    intent.setData(Uri.fromFile(new File(mediaPath)));
                                    new MediaWatcher().onReceive(ShareProofActivity.this, intent);
                                    return "message";
                                }

                                protected void onPostExecute(String msg) {
                                    // Post Code
                                    // Use `msg` in code
                                }
                            }.execute();


                            result = true;

                        }
                    }
                }
            }
        }

        cursor.close();

        return result;
    }

    private boolean processUri (Uri mediaUri, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia)
    {
        Cursor cursor = getContentResolver().query(mediaUri,      null,null, null, null);
        boolean result = false;

        if (cursor.getCount() > 0) {

            cursor.moveToFirst();
            String mediaPath = cursor.getString(cursor.getColumnIndex("_data"));

            if (mediaPath != null) {
                //check proof metadata against original image

                result = shareProof(mediaPath, shareUris, sb, fBatchProofOut, shareMedia);

                if (!result)
                    result = shareProofClassic(mediaPath, shareUris, sb, fBatchProofOut, shareMedia);


            }
        }

        cursor.close();

        return result;
    }

    private boolean shareProof (String mediaPath, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia)
    {

        String hash = HashUtils.getSHA256FromFileContent(new File(mediaPath));

        if (hash != null) {
            File fileMedia = new File(mediaPath);
            File fileFolder = MediaWatcher.getHashStorageDir(hash);

            if (fileFolder == null)
                return false;

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
        sb.append(getString(R.string.last_modified)).append(' ').append(new Date(fileMedia.lastModified()).toGMTString());
        sb.append(' ');
        sb.append(getString(R.string.has_hash)).append(' ').append(hash);
        sb.append("\n\n");
        sb.append(getString(R.string.proof_signed) + fingerprint);
        sb.append("\n");
        sb.append(getString(R.string.view_public_key) + fingerprint);
        sb.append("\n\n");

        try {
            final TimeBeatNotarizationProvider tbNotarize = new TimeBeatNotarizationProvider(this);
            String tbProof = tbNotarize.getProof(hash);
            sb.append(getString(R.string.independent_notary) + ' ' + tbProof);
        }
        catch (Exception ioe)
        {
            Timber.e("Error checking for Timebeat proof",ioe);
        }

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

    private void shareAll (boolean shareProof, String shareText, ArrayList<Uri> shareUris)
    {

        Intent shareIntent = null;

        if (shareUris.size() > 0) {

            if (!shareProof)
            {
                shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
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
    }

    private void shareFiltered(String shareMessage, String shareText, ArrayList<Uri> shareUris) {

        Intent emailIntent = new Intent();
        emailIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        // Native email client doesn't currently support HTML, but it doesn't
        // hurt to try in case they fix it
        emailIntent.setType("*/*");
        emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);

        PackageManager pm = getPackageManager();
        Intent sendIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        sendIntent.setType("*/*");

        List<ResolveInfo> resInfo = pm.queryIntentActivities(sendIntent, 0);
        ArrayList<LabeledIntent> intentList = new ArrayList();

        for (int i = 0; i < resInfo.size(); i++) {
            // Extract the label, append it, and repackage it in a LabeledIntent
            ResolveInfo ri = resInfo.get(i);
            String packageName = ri.activityInfo.packageName;
            if (packageName.contains("android.email")) {
                emailIntent.setPackage(packageName);
            } else if (packageName.contains("com.whatsapp")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("com.google.android.gm")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("com.google.android.apps.docs")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("com.dropbox")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("org.thoughtcrime")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("conversations")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
        }

        // convert intentList to array
        LabeledIntent[] extraIntents = intentList
                .toArray(new LabeledIntent[intentList.size()]);

        Intent openInChooser = Intent.createChooser(emailIntent,shareMessage);
        openInChooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents);
        startActivity(openInChooser);
    }

    private void askForPermission(String permission, Integer requestCode) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {

                //This is called if user has denied the permission before
                //In this case I am just asking the permission again
                ActivityCompat.requestPermissions(this, new String[]{permission}, requestCode);

            } else {

                ActivityCompat.requestPermissions(this, new String[]{permission}, requestCode);
            }
        } else {
        }
    }
}
