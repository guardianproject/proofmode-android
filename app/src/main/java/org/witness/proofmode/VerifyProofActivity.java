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

import org.bouncycastle.openpgp.PGPException;
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
                Toast.makeText(activity,activity.getString(R.string.proof_integrity_verified_success),Toast.LENGTH_LONG).show();

            }
            else {
                Toast.makeText(activity, activity.getString(R.string.proof_integrity_verified_failed),Toast.LENGTH_LONG).show();

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


}
