package org.witness.proofmode;

import static org.witness.proofmode.ProofMode.OPENPGP_FILE_TAG;
import static org.witness.proofmode.ProofMode.PROOF_FILE_TAG;
import static org.witness.proofmode.ProofMode.OPENTIMESTAMPS_FILE_TAG;

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
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.service.MediaWatcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import timber.log.Timber;

public class ShareProofActivity extends AppCompatActivity {

    private boolean sendMedia = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        setContentView(R.layout.activity_share);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("");
            getSupportActionBar().setDisplayShowHomeEnabled(false);
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }

        View tvInfoBasic = findViewById(R.id.tvInfoBasic);
        tvInfoBasic.setOnClickListener(v -> showInfoBasic());
        View tvInfoRobust = findViewById(R.id.tvInfoRobust);
        tvInfoRobust.setOnClickListener(v -> showInfoRobust());

        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();

        boolean proofExists = false;

        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);

            for (Uri mediaUri : mediaUris)
            {
                try {
                    proofExists = proofExists(mediaUri);
                } catch (FileNotFoundException e) {
                   Timber.w(e);
                   proofExists = false;
                }

                if (!proofExists)
                    break;
            }

            if (proofExists)
            {
                displaySharePrompt();
            }
            else {
                displayGeneratePrompt();
            }

        } else if (Intent.ACTION_SEND.equals(action)) {

            Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null)
            {
                mediaUri = cleanUri (mediaUri);

                try {
                    proofExists = proofExists(mediaUri);
                } catch (FileNotFoundException e) {
                    Timber.w(e);
                    proofExists = false;
                }
            }

            if (proofExists)
            {
                displaySharePrompt();
            }
            else {
                displayGeneratePrompt();
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

        boolean proofExists;

        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);

            boolean proofGenerated = false;

            for (Uri mediaUri : mediaUris)
            {
                mediaUri = cleanUri(mediaUri);

                try {
                    proofExists = proofExists(mediaUri);

                    if (!proofExists) {
                        generateProof(mediaUri);
                        proofGenerated = true;
                    }
                }
                catch (FileNotFoundException fe)
                {
                    Timber.d("FileNotFound: %s", mediaUri);
                }

            }

            if (!proofGenerated)
                displaySharePrompt ();


        } else if (Intent.ACTION_SEND.equals(action)) {

            Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null)
            {
                mediaUri = cleanUri(mediaUri);

                try {
                    proofExists = proofExists(mediaUri);
                }
                catch (FileNotFoundException fe)
                {
                    proofExists = false;
                }

                if (!proofExists)
                    generateProof(mediaUri);
            }
        }
    }

    public void clickNotarize (View button)
    {
        shareProof (false, false);

    }

    public void clickAll (View button)
    {
        shareProof (sendMedia, true);

    }

    private void displayProgress ()
    {

        findViewById(R.id.view_proof_progress).setVisibility(View.VISIBLE);
        findViewById(R.id.view_no_proof).setVisibility(View.GONE);
        findViewById(R.id.view_proof).setVisibility(View.GONE);

    }

    private void displayGeneratePrompt ()
    {

        findViewById(R.id.view_proof_progress).setVisibility(View.GONE);
        findViewById(R.id.view_no_proof).setVisibility(View.VISIBLE);
        findViewById(R.id.view_proof).setVisibility(View.GONE);
    }

    private void displaySharePrompt ()
    {
        findViewById(R.id.view_proof_progress).setVisibility(View.GONE);
        findViewById(R.id.view_no_proof).setVisibility(View.GONE);
        findViewById(R.id.view_proof).setVisibility(View.VISIBLE);
    }

    private void shareProof (final boolean shareMedia, final boolean shareProof) {

        displayProgress();

        new Thread(() -> {
            try {
                boolean result = shareProofAsync(shareMedia, shareProof);
                if (!result)
                {
                    //do something
                    Timber.d("unable to shareProofAsync");
                }

            } catch (FileNotFoundException e) {
                Timber.e(e);
                displayGeneratePrompt();

            }

        }).start();

    }

    private boolean shareProofAsync (boolean shareMedia, boolean shareProof) throws FileNotFoundException {

    // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();

        ArrayList<Uri> shareUris = new ArrayList<>();
        StringBuffer shareText = new StringBuffer ();

        if (Intent.ACTION_SEND_MULTIPLE.equals(action))
        {
            ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);

            File fileBatchProof;

            PrintWriter fBatchProofOut;

            try
            {
                File fileFolder = MediaWatcher.getHashStorageDir(this, "batch");

                if (fileFolder == null)
                    return false;

                fileBatchProof = new File(fileFolder,new Date().getTime() + "batchproof.csv");
                fBatchProofOut = new PrintWriter(new FileWriter(fileBatchProof,  true));
            }
            catch (IOException ioe) {
                return false; //unable to open batch proof
            }


            for (Uri mediaUri : mediaUris)
            {
                mediaUri = cleanUri(mediaUri);

                if (!processUri (mediaUri, shareUris, shareText, fBatchProofOut, shareMedia))
                    return false;
            }

            fBatchProofOut.flush();
            fBatchProofOut.close();
            Uri uriBatchProof = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider",fileBatchProof);
            shareUris.add(uriBatchProof); // Add your image URIs here


        }
        else if (Intent.ACTION_SEND.equals(action)) {

            Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (mediaUri == null)
                mediaUri = intent.getData();

            if (mediaUri != null) {
                mediaUri = cleanUri(mediaUri);

                if (!processUri(mediaUri, shareUris, shareText, null, shareMedia))
                    return false;
            }

        }

        if (shareUris.size() > 0) {

            if (!shareProof)
                shareNotarization(shareText.toString());
            else {

                File fileFolder = MediaWatcher.getHashStorageDir(this,"zip");

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-hh-mm-ss");
                String dateString = sdf.format(new Date());

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                PgpUtils pu = PgpUtils.getInstance(this,prefs.getString("password",PgpUtils.DEFAULT_PASSWORD));
                String userId = pu.getPublicKeyFingerprint();

                File fileZip = new File(fileFolder,"proofmode-" + userId + "-" + dateString + ".zip");
                zipProof(shareUris,fileZip);

                Uri uriZip = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider",fileZip);

                shareFiltered(getString(R.string.select_app), shareText.toString(), shareUris, uriZip);

            }
        }


        return true;
    }

    private boolean proofExists (Uri mediaUri) throws FileNotFoundException {
        boolean result = false;

        DocumentFile sourceFile = DocumentFile.fromSingleUri(this, mediaUri);
        if (sourceFile == null)
            return false;

        boolean mediaUriExists = sourceFile.exists();

        if (mediaUriExists) {
            String hash = HashUtils.getSHA256FromFileContent(getContentResolver().openInputStream(mediaUri));

            if (hash != null) {

                Timber.d("Proof check if exists for URI %s and hash %s", mediaUri, hash);


                File fileFolder = MediaWatcher.getHashStorageDir(this, hash);

                if (fileFolder != null) {
                    File fileMediaProof = new File(fileFolder, hash + PROOF_FILE_TAG);
                    //generate now?
                    result = fileMediaProof.exists();
                }
            }

            return result;
        }
        else
            throw new FileNotFoundException();

    }

    private static class ProofTask extends AsyncTask<Uri, Void, String> {

        private final WeakReference<ShareProofActivity> activityReference;

        // only retain a weak reference to the activity
        ProofTask(ShareProofActivity context) {
            super();
            activityReference = new WeakReference<>(context);
        }

        protected String doInBackground(Uri... params) {
            ProofMode.generateProof(activityReference.get(), params[0]);
            return "message";
        }

        protected void onPostExecute(String msg) {

            activityReference.get().displaySharePrompt();
        }
    }

    private void generateProof (final Uri mediaUri)
    {
        String[] projection = { MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_TAKEN };

        Cursor cursor = getContentResolver().query(getRealUri(mediaUri),      projection,null, null, null);

        if (cursor.getCount() > 0) {

            cursor.moveToFirst();

            int mediaPathCol = cursor.getColumnIndex(projection[0]);
            String mediaPath = null;

            if (mediaPathCol >= 0)
                mediaPath = cursor.getString(mediaPathCol);

            if (mediaPath != null) {

                new ProofTask(this).execute(Uri.fromFile(new File(mediaPath)));

            }
            else
            {
                final String tmpMediaPath = getImageUrlWithAuthority(getApplicationContext(),mediaUri);
                if (tmpMediaPath != null) {
                    final Intent intent = new Intent();
                    Uri tmpMediaUri = Uri.fromFile(new File(tmpMediaPath));
                    intent.setAction(Intent.ACTION_SEND);
                    intent.setDataAndType(tmpMediaUri, getIntent().getType());
                    setIntent(intent);

                    new ProofTask(this).execute(Uri.fromFile(new File(tmpMediaPath)));
                }
                else
                    showProofError();

            }
        }

        cursor.close();
    }

    private void showProofError ()
    {
        findViewById(R.id.view_proof).setVisibility(View.GONE);
        findViewById(R.id.view_no_proof).setVisibility(View.GONE);
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

    private boolean processUri (Uri mediaUri, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia) throws FileNotFoundException {
        String[] projection = { MediaStore.Images.Media.DATA };
        Cursor cursor = getContentResolver().query(getRealUri(mediaUri),      projection,null, null, null);
        boolean result = false;
        String mediaPath = null;

        if (cursor != null) {
            if (cursor.getCount() > 0) {

                cursor.moveToFirst();
                int columnIndex = cursor.getColumnIndexOrThrow(projection[0]);
                mediaPath = cursor.getString(columnIndex);

            }

            cursor.close();
        }
        else
        {
            File fileMedia = new File(mediaUri.getPath());
            if (fileMedia.exists())
                mediaPath = fileMedia.getAbsolutePath();
        }

        if (mediaPath != null) {
            //check proof metadata against original image

            File fileMedia = new File(mediaPath);
            result = shareProof(mediaUri, fileMedia, shareUris, sb, fBatchProofOut, shareMedia);

            if (!result)
                result = shareProofClassic(mediaUri, mediaPath, shareUris, sb, fBatchProofOut, shareMedia);

        }

        return result;
    }



    private boolean shareProof (Uri uriMedia, File fileMedia, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia) throws FileNotFoundException {

        String hash = HashUtils.getSHA256FromFileContent(getContentResolver().openInputStream(uriMedia));

        if (hash != null) {
            File fileFolder = MediaWatcher.getHashStorageDir(this,hash);

            if (fileFolder == null)
                return false;

            File fileMediaSig = new File(fileFolder, hash + OPENPGP_FILE_TAG);
            File fileMediaProof = new File(fileFolder, hash + PROOF_FILE_TAG);
            File fileMediaProofSig = new File(fileFolder, hash + PROOF_FILE_TAG + OPENPGP_FILE_TAG);
            File fileMediaOpentimestamps = new File(fileFolder, hash + OPENTIMESTAMPS_FILE_TAG);


            if (fileMediaProof.exists()) {
                generateProofOutput(fileMedia, new Date(fileMedia.lastModified()), fileMediaSig, fileMediaProof, fileMediaProofSig, fileMediaOpentimestamps, hash, shareMedia, fBatchProofOut, shareUris, sb);
                return true;
            }
        }

        return false;

    }

    private boolean shareProofClassic (Uri mediaUri, String mediaPath, ArrayList<Uri> shareUris, StringBuffer sb, PrintWriter fBatchProofOut, boolean shareMedia) throws FileNotFoundException {

        String baseFolder = "proofmode";

        String hash = HashUtils.getSHA256FromFileContent(getContentResolver().openInputStream(mediaUri));

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

        generateProofOutput(fileMedia, new Date(fileMedia.lastModified()), fileMediaSig, fileMediaProof, fileMediaProofSig, null, hash, shareMedia, fBatchProofOut, shareUris, sb);

        return false;
    }

    private void generateProofOutput (File fileMedia, Date lastModified, File fileMediaSig, File fileMediaProof, File fileMediaProofSig, File fileMediaNotary, String hash, boolean shareMedia, PrintWriter fBatchProofOut, ArrayList<Uri> shareUris, StringBuffer sb)
    {
        DateFormat sdf = SimpleDateFormat.getDateTimeInstance();

        String fingerprint = PgpUtils.getInstance(this).getPublicKeyFingerprint();

        sb.append(fileMedia.getName()).append(' ');
        sb.append(getString(R.string.last_modified)).append(' ').append(sdf.format(lastModified));
        sb.append(' ');
        sb.append(getString(R.string.has_hash)).append(' ').append(hash);
        sb.append("\n\n");
        sb.append(getString(R.string.proof_signed)).append(fingerprint);
        sb.append("\n");

        shareUris.add(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider",fileMediaProof));

        if (shareMedia) {
            if (fileMedia.exists())
                shareUris.add(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider",fileMedia));

            if (fileMediaSig.exists())
                shareUris.add(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider",fileMediaSig));

            if (fileMediaProofSig.exists())
                shareUris.add(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider",fileMediaProofSig));

            if (fileMediaNotary.exists())
                shareUris.add(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider",fileMediaNotary));

        }

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
            {
                Timber.d(ioe);
            }
        }
    }

    private void shareNotarization (String shareText)
    {

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        shareIntent.setType("*/*");

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_notarization)));

    }

    private void shareFilteredSingle(String shareMessage, String shareText, Uri shareUri, String shareMimeType) {



        PackageManager pm = getPackageManager();
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
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
                intent.setAction(Intent.ACTION_SEND);
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
                intent.setAction(Intent.ACTION_SEND);
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

                intent.setAction(Intent.ACTION_SEND);
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

                intent.setAction(Intent.ACTION_SEND);
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

                intent.setAction(Intent.ACTION_SEND);
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

                intent.setAction(Intent.ACTION_SEND);
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

                intent.setAction(Intent.ACTION_SEND);
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
        baseIntent.setAction(Intent.ACTION_SEND);

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
        shareIntent.setAction(Intent.ACTION_SEND);

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

    public void zipProof(ArrayList<Uri> uris, File fileZip) {
        try {
            BufferedInputStream origin;
            FileOutputStream dest = new FileOutputStream(fileZip);
            ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                    dest));
            byte[] data = new byte[BUFFER];

            for (Uri uri : uris) {
                origin = new BufferedInputStream(getContentResolver().openInputStream(uri), BUFFER);

                ZipEntry entry = new ZipEntry(uri.getLastPathSegment());
                out.putNextEntry(entry);
                int count;

                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
                origin.close();
            }

            //add public key
            String pubKey = getPublicKey();
            ZipEntry entry = new ZipEntry("pubkey.asc");
            out.putNextEntry(entry);
            out.write(pubKey.getBytes());

            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getImageUrlWithAuthority(Context context, Uri uri) {
        InputStream is = null;
        if (uri.getAuthority() != null) {
            try {
                is = context.getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                return writeToTempImageAndGetPathUri(context, bmp).toString();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }finally {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
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

    private String getPublicKey () {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        PgpUtils pu = PgpUtils.getInstance(this,prefs.getString("password",PgpUtils.DEFAULT_PASSWORD));
        String pubKey = null;

        try {
            pubKey = pu.getPublicKey();
        } catch (IOException e) {
            Timber.d("error getting public key");
        }

        return pubKey;
    }
}
