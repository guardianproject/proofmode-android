package org.witness.proofmode;

import static android.content.Intent.ACTION_SEND;
import static org.witness.proofmode.ProofMode.GOOGLE_SAFETYNET_FILE_TAG;
import static org.witness.proofmode.ProofMode.OPENPGP_FILE_TAG;
import static org.witness.proofmode.ProofMode.OPENTIMESTAMPS_FILE_TAG;
import static org.witness.proofmode.ProofMode.PROOF_FILE_JSON_TAG;
import static org.witness.proofmode.ProofMode.PROOF_FILE_TAG;

import android.Manifest;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.pgp.PgpUtils;
import org.witness.proofmode.service.MediaWatcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import timber.log.Timber;

public class VerifyProofActivity extends AppCompatActivity {

    private boolean sendMedia = true;

    private final static String ZIP_FILE_DATETIME_FORMAT = "yyyy-MM-dd-HH-mm-ssz";

    private HashMap<String, String> hashCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        setContentView(R.layout.activity_verify);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("");
            getSupportActionBar().setDisplayShowHomeEnabled(false);
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }


        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();


        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);

            //just check the first file
            if (mediaUris.size() > 0)
            {
                displayProgress(getString(R.string.progress_checking_proof));
                new VerifyProofTasks(this).execute(mediaUris.get(0));
            }


        } else if (ACTION_SEND.equals(action) || action.endsWith("SHARE_PROOF")) {

            intent.setAction(ACTION_SEND);

            Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null)
            {
                mediaUri = cleanUri(mediaUri);
                displayProgress(getString(R.string.progress_checking_proof));
                new VerifyProofTasks(this).execute(mediaUri);
            }


        }
        else
            finish();

    }

    private Uri cleanUri (Uri mediaUri)
    {
        //content://com.google.android.apps.photos.contentprovider/0/1/content%3A%2F%2Fmedia%2Fexternal%2Fimages%2Fmedia%2F3517/ORIGINAL/NONE/image%2Fjpeg/765892976
        Uri resultUri = mediaUri;

        String contentEnc = "content://";
        List<String> paths = mediaUri.getPathSegments();
        for (String path: paths)
        {
            if (path.startsWith(contentEnc))
            {
                try {
                    String pathDec = URLDecoder.decode(path,"UTF-8");
                    resultUri = Uri.parse(pathDec);
                    break;
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }

        return resultUri;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_share, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Respond to the action bar's Up/Home button
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void generateProof (View button) throws FileNotFoundException {

        findViewById(R.id.view_proof_progress).setVisibility(View.VISIBLE);
        findViewById(R.id.view_no_proof).setVisibility(View.GONE);

        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();

        String proofHash = null;

        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            final ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);


        } else if (ACTION_SEND.equals(action) || action.endsWith("SHARE_PROOF")) {

            Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null)
            {
                mediaUri = cleanUri(mediaUri);

                try {
                    proofHash = hashCache.get(mediaUri.toString());

                    if (proofHash != null)
                        proofHash = HashUtils.getSHA256FromFileContent(getContentResolver().openInputStream(mediaUri));

                   // generateProof(mediaUri, proofHash);
                }
                catch (FileNotFoundException fe)
                {
                    proofHash = null;
                }

            }
        }
    }



    private void displayProgress (String progressText)
    {

        findViewById(R.id.view_verify).setVisibility(View.VISIBLE);

        if (!TextUtils.isEmpty(progressText)) {
            ((TextView) findViewById(R.id.verify_body)).setText(progressText);
            findViewById(R.id.progressBarVerify).setVisibility(View.VISIBLE);
        }
        else {
            ((TextView) findViewById(R.id.verify_body)).setText("");
            findViewById(R.id.progressBarVerify).setVisibility(View.GONE);
        }

    }




    private static class VerifyProofTasks extends AsyncTask<Uri, Void, Boolean> {

        private final VerifyProofActivity activity;

        VerifyProofTasks(VerifyProofActivity context) {
            super();
            activity = context;
        }

        protected Boolean doInBackground (Uri... params) {

            Uri proofZipUri = params[0];

            if (proofZipUri != null)
            {
                //mediaUri = activity.cleanUri (mediaUri);

                try {
                   // InputStream is = activity.getContentResolver().openInputStream(proofZipUri);
                    return ProofMode.verifyProofZip(activity, proofZipUri);
                } catch (Exception e) {
                    e.printStackTrace();
                }


            }

            return false;

        }

        protected void onPostExecute(Boolean verified) {

            if (verified)
            {
                Toast.makeText(activity,"Verified!",Toast.LENGTH_LONG).show();

            }
            else {
                Toast.makeText(activity,"NOT Verified!",Toast.LENGTH_LONG).show();

            }

            activity.finish();
        }
    }



    private void showProofError ()
    {
        findViewById(R.id.view_proof).setVisibility(View.GONE);
        findViewById(R.id.view_no_proof).setVisibility(View.GONE);
        findViewById(R.id.view_proof_progress).setVisibility(View.GONE);
        findViewById(R.id.view_proof_saved).setVisibility(View.GONE);
        findViewById(R.id.view_proof_failed).setVisibility(View.VISIBLE);
    }


    private Uri getRealUri (Uri contentUri)
    {
        String unusablePath = contentUri.getPath();
        int startIndex = unusablePath.indexOf("external/");
        int endIndex = unusablePath.indexOf("/ACTUAL");
        if (startIndex != -1 && endIndex != -1) {
            String embeddedPath = unusablePath.substring(startIndex, endIndex);

            Uri.Builder builder = contentUri.buildUpon();
            builder.path(embeddedPath);
            builder.authority("media");
            return builder.build();
        }
        else
            return contentUri;
    }

    private boolean processUri (String mediaHash, Uri mediaUri, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia, boolean isFirstProof) throws FileNotFoundException {


        String[] projection = new String[1];
        String mimeType = getContentResolver().getType(mediaUri);

        if (mimeType != null)
        {
            if (mimeType.startsWith("image"))
                projection[0] = MediaStore.Images.Media.DATA;
            else if (mimeType.startsWith("video"))
                projection[0] = MediaStore.Video.Media.DATA;
            else if (mimeType.startsWith("audio"))
                projection[0] = MediaStore.Audio.Media.DATA;
        }
        else
          projection[0] = MediaStore.Images.Media.DATA;

        Cursor cursor = getContentResolver().query(getRealUri(mediaUri),      projection,null, null, null);
        boolean result = false;
        String mediaPath = null;

        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                try {
                    int columnIndex = cursor.getColumnIndexOrThrow(projection[0]);
                    mediaPath = cursor.getString(columnIndex);
                }
                catch (Exception e) {
                    //couldn't find path
                }
            }

            cursor.close();
        }

        if (TextUtils.isEmpty(mediaPath))
        {
            File fileMedia = new File(mediaUri.getPath());
            if (fileMedia.exists())
                mediaPath = fileMedia.getAbsolutePath();
        }

        if (mediaPath != null) {
            //check proof metadata against original image

            File fileMedia = new File(mediaPath);
            result = shareProof(mediaHash, mediaUri, fileMedia, shareUris, sb, fBatchProofOut, shareMedia, isFirstProof);

            if (!result)
                result = shareProofClassic(mediaUri, mediaPath, shareUris, sb, fBatchProofOut, shareMedia);

        }
        else
        {
            result = shareProof(mediaHash, mediaUri, null, shareUris, sb, fBatchProofOut, shareMedia, isFirstProof);

        }

        return result;
    }



    private boolean shareProof (String hash, Uri uriMedia, File fileMedia, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia, boolean isFirstProof) throws FileNotFoundException {

        if (hash == null)
            hash = HashUtils.getSHA256FromFileContent(getContentResolver().openInputStream(uriMedia));

        if (hash != null) {
            File fileFolder = MediaWatcher.getHashStorageDir(this,hash);

            if (fileFolder == null)
                return false;

            File fileMediaSig = new File(fileFolder, hash + OPENPGP_FILE_TAG);
            File fileMediaProof = new File(fileFolder, hash + PROOF_FILE_TAG);
            File fileMediaProofSig = new File(fileFolder, hash + PROOF_FILE_TAG + OPENPGP_FILE_TAG);
            File fileMediaProofJSON = new File(fileFolder, hash + PROOF_FILE_JSON_TAG);
            File fileMediaProofJSONSig = new File(fileFolder, hash + PROOF_FILE_JSON_TAG + OPENPGP_FILE_TAG);

            File fileMediaOpentimestamps = new File(fileFolder, hash + OPENTIMESTAMPS_FILE_TAG);
            File fileMediaGoogleSafetyNet = new File(fileFolder, hash + GOOGLE_SAFETYNET_FILE_TAG);


            if (fileMediaProof.exists()) {
                Date lastModified = null;
                if (fileMedia != null)
                    lastModified = new Date(fileMedia.lastModified());

                try {
                    generateProofOutput(uriMedia, fileMedia, lastModified, fileMediaSig, fileMediaProof, fileMediaProofSig, fileMediaProofJSON, fileMediaProofJSONSig, fileMediaOpentimestamps, fileMediaGoogleSafetyNet, hash, shareMedia, fBatchProofOut, shareUris, sb, isFirstProof);
                    return true;
                } catch (IOException e) {
                    Timber.d(e,"unable to geenrate proof output");
                    return false;
                }
            }
        }

        return false;

    }

    private boolean shareProofClassic (Uri mediaUri, String mediaPath, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia) throws FileNotFoundException {

        String baseFolder = "proofmode";

        String hash = HashUtils.getSHA256FromFileContent(getContentResolver().openInputStream(mediaUri));

        File fileMedia = new File(mediaPath);
        File fileMediaSig = new File(mediaPath + OPENPGP_FILE_TAG);
        File fileMediaProof = new File(mediaPath + PROOF_FILE_TAG);
        File fileMediaProofSig = new File(fileMediaProof.getAbsolutePath() + OPENPGP_FILE_TAG);

        //if not there try alternate locations
        if (!fileMediaSig.exists())
        {
            fileMediaSig = new File(Environment.getExternalStorageDirectory(),baseFolder + mediaPath + ".asc");
            fileMediaProof = new File(Environment.getExternalStorageDirectory(),baseFolder + mediaPath + PROOF_FILE_TAG);
            fileMediaProofSig = new File(fileMediaProof.getAbsolutePath() + OPENPGP_FILE_TAG);

            if (!fileMediaSig.exists())
            {
                fileMediaSig = new File(getExternalFilesDir(null),mediaPath + OPENPGP_FILE_TAG);
                fileMediaProof = new File(getExternalFilesDir(null),mediaPath + PROOF_FILE_TAG);
                fileMediaProofSig = new File(fileMediaProof.getAbsolutePath() + OPENPGP_FILE_TAG);
            }

        }

        try {

            generateProofOutput(mediaUri, fileMedia, new Date(fileMedia.lastModified()), fileMediaSig, fileMediaProof, fileMediaProofSig, null, null, null, null, hash, shareMedia, fBatchProofOut, shareUris, sb, true);
            return true;
        }
        catch (IOException ioe)
        {
            Timber.d(ioe,"unable to generate classic proof");
            return false;
        }
    }

    private void generateProofOutput (Uri uriMedia, File fileMedia, Date fileLastModified, File fileMediaSig, File fileMediaProof, File fileMediaProofSig, File fileMediaProofJSON, File fileMediaProofJSONSig, File fileMediaNotary, File fileMediaNotary2, String hash, boolean shareMedia, PrintWriter fBatchProofOut, ArrayList<Uri> shareUris, StringBuffer sb, boolean isFirstProof) throws IOException {
        DateFormat sdf = SimpleDateFormat.getDateTimeInstance();

        String fingerprint = PgpUtils.getInstance(this).getPublicKeyFingerprint();

        if (fileMedia != null) {
            sb.append(fileMedia.getName()).append(' ');
            sb.append(getString(R.string.last_modified)).append(' ').append(sdf.format(fileLastModified));
            sb.append(' ');
        }

        sb.append(getString(R.string.has_hash)).append(' ').append(hash);
        sb.append("\n\n");
        sb.append(getString(R.string.proof_signed)).append(fingerprint);
        sb.append("\n");

        //shareUris.add(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + PROVIDER_TAG,fileMediaProof));
        shareUris.add(Uri.fromFile(fileMediaProof));

        if (shareMedia) {

            shareUris.add(uriMedia);

            if (fileMediaSig != null
               && fileMediaSig.exists())
                shareUris.add(Uri.fromFile(fileMediaSig));

            if (fileMediaProofSig != null
              && fileMediaProofSig.exists())
                shareUris.add(Uri.fromFile(fileMediaProofSig));

            if (fileMediaProofJSON != null
                    && fileMediaProofJSON.exists())
                shareUris.add(Uri.fromFile(fileMediaProofJSON));


            if (fileMediaProofJSONSig != null
                    && fileMediaProofJSONSig.exists())
                shareUris.add(Uri.fromFile(fileMediaProofJSONSig));


            if (fileMediaNotary != null
                    && fileMediaNotary.exists())
                shareUris.add(Uri.fromFile(fileMediaNotary));

            if (fileMediaNotary2 != null
                    && fileMediaNotary2.exists())
                shareUris.add(Uri.fromFile(fileMediaNotary2));

        }

        if (fBatchProofOut != null)
        {

                BufferedReader br = new BufferedReader(new FileReader(fileMediaProof));

                if (!isFirstProof) {
                    br.readLine();//skip header
                }
                else {
                    //get header from proof
                    fBatchProofOut.println(br.readLine());
                }

                String csvLine = br.readLine();
                fBatchProofOut.println(csvLine);
                br.close();

        }
    }

    private void shareNotarization (String shareText)
    {

        Intent shareIntent = new Intent(ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        shareIntent.setType("*/*");

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_notarization)));

    }

    private void shareFilteredSingle(String shareMessage, String shareText, Uri shareUri, String shareMimeType) {



        PackageManager pm = getPackageManager();
        Intent sendIntent = new Intent(ACTION_SEND);
        sendIntent.setType("*/*");

        List<ResolveInfo> resInfo = pm.queryIntentActivities(sendIntent, 0);
        ArrayList<LabeledIntent> intentList = new ArrayList<>();

        for (int i = 0; i < resInfo.size(); i++) {
            // Extract the label, append it, and repackage it in a LabeledIntent
            ResolveInfo ri = resInfo.get(i);
            String packageName = ri.activityInfo.packageName;
            if (packageName.contains("android.email")) {
                Intent intent = new Intent();
                intent.setPackage(packageName);

                intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_STREAM,shareUri);
                intent.putExtra(Intent.EXTRA_TITLE,shareUri.getLastPathSegment());
                intent.putExtra(Intent.EXTRA_SUBJECT,shareUri.getLastPathSegment());

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));

            } else if (packageName.contains("com.whatsapp")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));
                intent.setAction(ACTION_SEND);
                intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putExtra(Intent.EXTRA_STREAM,shareUri);

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("com.google.android.gm")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));
                intent.setAction(ACTION_SEND);
         //       intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_EMAIL, new String[] { "" });
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putExtra(Intent.EXTRA_STREAM,shareUri);
                intent.putExtra(Intent.EXTRA_TITLE,shareUri.getLastPathSegment());
                intent.putExtra(Intent.EXTRA_SUBJECT,shareUri.getLastPathSegment());

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("com.google.android.apps.docs")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));

                intent.setAction(ACTION_SEND);
           //     intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putExtra(Intent.EXTRA_STREAM,shareUri);
                intent.putExtra(Intent.EXTRA_TITLE,shareUri.getLastPathSegment());
                intent.putExtra(Intent.EXTRA_SUBJECT,shareUri.getLastPathSegment());

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("com.dropbox")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));

                intent.setAction(ACTION_SEND);
            //    intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putExtra(Intent.EXTRA_STREAM,shareUri);
                intent.putExtra(Intent.EXTRA_TITLE,shareUri.getLastPathSegment());
                intent.putExtra(Intent.EXTRA_SUBJECT,shareUri.getLastPathSegment());

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("org.thoughtcrime")) {

                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));

                intent.setAction(ACTION_SEND);
           //     intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putExtra(Intent.EXTRA_STREAM,shareUri);
                intent.putExtra(Intent.EXTRA_TITLE,shareUri.getLastPathSegment());
                intent.putExtra(Intent.EXTRA_SUBJECT,shareUri.getLastPathSegment());

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("conversations")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));

                intent.setAction(ACTION_SEND);
                intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putExtra(Intent.EXTRA_STREAM,shareUri);
                intent.putExtra(Intent.EXTRA_TITLE,shareUri.getLastPathSegment());
                intent.putExtra(Intent.EXTRA_SUBJECT,shareUri.getLastPathSegment());

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }
            else if (packageName.contains("org.awesomeapp")||packageName.contains("im.zom")) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName,
                        ri.activityInfo.name));

                intent.setAction(ACTION_SEND);
                intent.setDataAndType(shareUri, shareMimeType);
                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                intent.putExtra(Intent.EXTRA_STREAM,shareUri);
                intent.putExtra(Intent.EXTRA_TITLE,shareUri.getLastPathSegment());
                intent.putExtra(Intent.EXTRA_SUBJECT,shareUri.getLastPathSegment());

                intentList.add(new LabeledIntent(intent, packageName, ri
                        .loadLabel(pm), ri.icon));
            }

        }

        Intent baseIntent = new Intent();
        baseIntent.setAction(ACTION_SEND);

        // convert intentList to array
        LabeledIntent[] extraIntents = intentList
                .toArray(new LabeledIntent[intentList.size()]);

        Intent openInChooser = Intent.createChooser(baseIntent,shareMessage);
        openInChooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents);
        startActivity(openInChooser);
    }

    private void shareFiltered(String shareMessage, String shareText, ArrayList<Uri> shareUris, Uri shareZipUri) {

        int modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;

        Intent shareIntent = new Intent();
        shareIntent.setAction(ACTION_SEND);

        shareIntent.setDataAndType(shareZipUri,"application/zip");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        shareIntent.putExtra(Intent.EXTRA_STREAM, shareZipUri);

        shareIntent.addFlags(modeFlags);

        Intent openInChooser = Intent.createChooser(shareIntent,shareMessage);
        openInChooser.addFlags(modeFlags);

        List<ResolveInfo> resInfoList = this.getPackageManager().queryIntentActivities(openInChooser, 0);

        for (ResolveInfo resolveInfo : resInfoList) {
            String packageName = resolveInfo.activityInfo.packageName;

            if (shareUris != null)
                for (Uri uri : shareUris)
                    grantUriPermission(packageName, uri, modeFlags);

            if (shareZipUri != null)
                grantUriPermission(packageName, shareZipUri, modeFlags);

        }

        startActivity(openInChooser);
    }

    private void askForPermission(String permission, Integer requestCode) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            ActivityCompat.requestPermissions(this, new String[]{permission}, requestCode);
        }
    }

    private final static int BUFFER = 1024*8;

    public void zipProof(ArrayList<Uri> uris, File fileZip) throws IOException {

        BufferedInputStream origin;
        FileOutputStream dest = new FileOutputStream(fileZip);
        ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                dest));
        byte[] data = new byte[BUFFER];

        for (Uri uri : uris) {
            try {
                String fileName = getFileNameFromUri(uri);
                Timber.d("adding to zip: " + fileName);
                origin = new BufferedInputStream(getContentResolver().openInputStream(uri), BUFFER);
                ZipEntry entry = new ZipEntry(fileName);
                out.putNextEntry(entry);
                int count;

                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
                origin.close();
            }
            catch (Exception e)
            {
                Timber.d(e, "Failed adding URI to zip: " + uri.getLastPathSegment());
            }
        }

        Timber.d("Adding public key");
        //add public key
        String pubKey = ProofMode.getPublicKeyString(this);
        ZipEntry entry = new ZipEntry("pubkey.asc");
        out.putNextEntry(entry);
        out.write(pubKey.getBytes());

        Timber.d("Adding HowToVerifyProofData.txt");
        String howToFile = "HowToVerifyProofData.txt";
        entry = new ZipEntry(howToFile);
        out.putNextEntry(entry);
        InputStream is = getResources().getAssets().open(howToFile);
        byte[] buffer = new byte[1024];
        for (int length = is.read(buffer); length != -1; length = is.read(buffer)) {
            out.write(buffer, 0, length);
        }
        is.close();

        Timber.d("Zip complete");

        out.close();

    }

    private String getFileNameFromUri (Uri uri)
    {

        String[] projection = new String[2];

        String mimeType = getContentResolver().getType(uri);
        MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
        String fileExt = mimeTypeMap.getExtensionFromMimeType(mimeType);

        if (mimeType != null)
        {
            if (mimeType.startsWith("image")) {
                projection[0] = MediaStore.Images.Media.DATA;
                projection[1] = MediaStore.Images.Media.DISPLAY_NAME;
            }
            else if (mimeType.startsWith("video")) {
                projection[0] = MediaStore.Video.Media.DATA;
                projection[1] = MediaStore.Video.Media.DISPLAY_NAME;

            }
            else if (mimeType.startsWith("audio")) {
                projection[0] = MediaStore.Audio.Media.DATA;
                projection[1] = MediaStore.Audio.Media.DISPLAY_NAME;
            }

        }
        else {
            projection[0] = MediaStore.Images.Media.DATA;
            projection[1] = MediaStore.Images.Media.DISPLAY_NAME;
         }

        Cursor cursor = getContentResolver().query(getRealUri(uri),      projection,null, null, null);
        boolean result = false;

        //default name with file extension
        String fileName = uri.getLastPathSegment();

        if (fileExt != null && fileName.indexOf(".")==-1)
            fileName += "." + fileExt;


        if (cursor != null) {
            if (cursor.getCount() > 0) {

                cursor.moveToFirst();

                try {

                    int columnIndex = cursor.getColumnIndexOrThrow(projection[0]);
                    String path = cursor.getString(columnIndex);
                    if (path != null) {
                        File fileMedia = new File(path);
                        if (fileMedia.exists())
                            fileName = fileMedia.getName();

                    }

                    if (TextUtils.isEmpty(fileName)) {
                        columnIndex = cursor.getColumnIndexOrThrow(projection[1]);
                        fileName = cursor.getString(columnIndex);
                    }
                }
                catch (IllegalArgumentException iae) {

                }
            }

            cursor.close();
        }

        if (TextUtils.isEmpty(fileName))
            fileName = uri.getLastPathSegment();

        return fileName;
    }

    public static Uri writeToTempImageAndGetPathUri(Context inContext, Bitmap inImage) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        inImage.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
        String path = MediaStore.Images.Media.insertImage(inContext.getContentResolver(), inImage, "Title", null);
        return Uri.parse(path);
    }

    private void showInfoBasic() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setView(R.layout.dialog_share_basic);
        final Dialog currentDialog = builder.create();
        currentDialog.show();
        currentDialog.findViewById(R.id.btnClose).setOnClickListener(v -> currentDialog.dismiss());
        currentDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private void showInfoRobust() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setView(R.layout.dialog_share_robust);
        final Dialog currentDialog = builder.create();
        currentDialog.show();
        currentDialog.findViewById(R.id.btnClose).setOnClickListener(v -> currentDialog.dismiss());
        CheckBox checkBox = currentDialog.findViewById(R.id.checkSendMedia);
        checkBox.setChecked(sendMedia);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> sendMedia = isChecked);
        currentDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }


    /**
     * User the PermissionActivity to ask for permissions, but show no UI when calling from here.
     */
    private boolean askForWritePermissions() {
        String[] requiredPermissions = new String[] {
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
        };

        if (!PermissionActivity.hasPermissions(this, requiredPermissions)) {
            Intent intent = new Intent(this, PermissionActivity.class);
            intent.putExtra(PermissionActivity.ARG_PERMISSIONS, requiredPermissions);
            startActivityForResult(intent, 9999);
            return true;
        }
        return false;
    }
}
