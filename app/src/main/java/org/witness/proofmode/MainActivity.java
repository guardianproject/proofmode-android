package org.witness.proofmode;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyRingGenerator;
import org.spongycastle.openpgp.PGPUtil;
import org.witness.proofmode.crypto.DetachedSignatureProcessor;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.service.MediaListenerService;
import org.witness.proofmode.util.GPSTracker;

import java.io.IOException;
import java.security.Security;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences mPrefs;

    private final static String URL_ABOUT = "https://guardianproject.info/apps/camerav";
    private final static int REQUEST_CODE_INTRO = 9999;

    private PgpUtils mPgpUtils;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mPgpUtils = PgpUtils.getInstance(this,mPrefs.getString("password",PgpUtils.DEFAULT_PASSWORD));

        SwitchCompat switchProof = (SwitchCompat)findViewById(R.id.switchProof);
        switchProof.setChecked(mPrefs.getBoolean("doProof",true));

        switchProof.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                mPrefs.edit().putBoolean("doProof",isChecked).commit();

            }
        });

        SwitchCompat switchLocation = (SwitchCompat)findViewById(R.id.switchLocation);
        switchLocation.setChecked(mPrefs.getBoolean("trackLocation",true));
        switchLocation.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                mPrefs.edit().putBoolean("trackLocation",isChecked).commit();

                if (isChecked)
                {
                    askForPermission(Manifest.permission.ACCESS_FINE_LOCATION, 1);
                    refreshLocation();
                }
            }
        });

        SwitchCompat switchDevice = (SwitchCompat)findViewById(R.id.switchDevice);
        switchDevice.setChecked(mPrefs.getBoolean("trackDeviceId",true));
        switchDevice.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                mPrefs.edit().putBoolean("trackDeviceId",isChecked).commit();

            }
        });

        SwitchCompat switchNotarize = (SwitchCompat)findViewById(R.id.switchNotarize);
        switchNotarize.setChecked(mPrefs.getBoolean("autoNotarize",true));
        switchNotarize.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                mPrefs.edit().putBoolean("autoNotarize",isChecked).commit();

            }
        });

        if (mPrefs.getBoolean("firsttime",true)) {
            startActivityForResult(new Intent(this, PMAppIntro.class),REQUEST_CODE_INTRO);
            mPrefs.edit().putBoolean("firsttime",false).commit();
        }
        else
        {
            askForPermission(Manifest.permission.ACCESS_FINE_LOCATION, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        SwitchCompat switchProof = (SwitchCompat)findViewById(R.id.switchProof);
        switchProof.setChecked(mPrefs.getBoolean("doProof",true));

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            //Location
            case 1:
                askForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE,2);
                break;
            //Call
            case 2:
                askForPermission(Manifest.permission.READ_EXTERNAL_STORAGE,3);

                break;

            case 3:
                askForPermission(Manifest.permission.ACCESS_NETWORK_STATE,4);

                break;

        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_about){

            startActivity(new Intent(this,PMAppIntro.class));

            return true;
        }
        else if (id == R.id.action_publish_key){

            publishKey();

            return true;
        }
        else if (id == R.id.action_share_key){

            shareKey();

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void askForPermission(String permission, Integer requestCode) {
        if (ContextCompat.checkSelfPermission(MainActivity.this, permission) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this, permission)) {

                //This is called if user has denied the permission before
                //In this case I am just asking the permission again
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, requestCode);

            } else {

                ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, requestCode);
            }
        } else {
        }
    }

    private void publishKey ()
    {


        try {
            mPgpUtils.publishPublicKey();
            String fingerprint = mPgpUtils.getPublicKeyFingerprint();

            Toast.makeText(this, R.string.open_public_key_page, Toast.LENGTH_LONG).show();

            openUrl("https://pgp.mit.edu/pks/lookup?op=get&search=0x" + fingerprint);
        }
        catch (IOException ioe)
        {
            Log.e("Proofmode","error publishing key",ioe);
        }
    }

    private void shareKey ()
    {


        try {
            mPgpUtils.publishPublicKey();
            String pubKey = mPgpUtils.getPublicKey();

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT,pubKey);
            startActivity(intent);
        }
        catch (IOException ioe)
        {
            Log.e("Proofmode","error publishing key",ioe);
        }
    }

    private void openUrl (String url)
    {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse(url));
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_INTRO)
        {
            askForPermission(Manifest.permission.ACCESS_FINE_LOCATION, 1);
        }
    }

    private void refreshLocation ()
    {
        GPSTracker gpsTracker = new GPSTracker(this);
        if (gpsTracker.canGetLocation()) {
            gpsTracker.getLocation();
        }
    }
}
