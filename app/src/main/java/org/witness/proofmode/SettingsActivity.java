package org.witness.proofmode;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.Toast;

import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.onboarding.OnboardingActivity;
import org.witness.proofmode.util.GPSTracker;

import java.io.IOException;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences mPrefs;

    private PgpUtils mPgpUtils;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        SwitchCompat switchProof = findViewById(R.id.switchProof);
        switchProof.setChecked(mPrefs.getBoolean("doProof",true));

        switchProof.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                mPrefs.edit().putBoolean("doProof",isChecked).commit();
                if (isChecked)
                {
                    askForPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, 1);
                }
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

        SwitchCompat switchMobile = (SwitchCompat)findViewById(R.id.switchCellInfo);
        switchMobile.setChecked(mPrefs.getBoolean("trackMobileNetwork",false));
        switchMobile.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                mPrefs.edit().putBoolean("trackMobileNetwork",isChecked).commit();

                if (isChecked)
                {
                    askForPermission(Manifest.permission.READ_PHONE_STATE, 1);
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

            case 4:
                askForPermission(Manifest.permission.READ_PHONE_STATE, 5);
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
    public void onBackPressed() {
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == android.R.id.home) {
            finish();
            return true;
        }
        else if (id == R.id.action_about){

            startActivity(new Intent(this,OnboardingActivity.class));

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

    private boolean askForPermission(String permission, Integer requestCode) {
        String[] permissions = new String[] { permission };
        if (!PermissionActivity.hasPermissions(this, permissions)) {
            Intent intent = new Intent(this, PermissionActivity.class);
            intent.putExtra(PermissionActivity.ARG_PERMISSIONS, permissions);
            intent.putExtra(PermissionActivity.ARG_LAYOUT_ID, R.layout.permission_location);
            startActivityForResult(intent, requestCode);
            return true;
        }
        return false;
    }

    private void publishKey ()
    {

        try {
            if (mPgpUtils == null)
                mPgpUtils = PgpUtils.getInstance(this,mPrefs.getString("password",PgpUtils.DEFAULT_PASSWORD));

            mPgpUtils.publishPublicKey();
            String fingerprint = mPgpUtils.getPublicKeyFingerprint();

            Toast.makeText(this, R.string.open_public_key_page, Toast.LENGTH_LONG).show();

            openUrl(PgpUtils.URL_LOOKUP_ENDPOINT + fingerprint);
        }
        catch (IOException ioe)
        {
            Log.e("Proofmode","error publishing key",ioe);
        }
    }

    private void shareKey ()
    {


        try {

            if (mPgpUtils == null)
                mPgpUtils = PgpUtils.getInstance(this,mPrefs.getString("password",PgpUtils.DEFAULT_PASSWORD));

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
        //TODO
    }

    private void refreshLocation ()
    {
        GPSTracker gpsTracker = new GPSTracker(this);
        if (gpsTracker.canGetLocation()) {
            gpsTracker.getLocation();
        }
    }


    private void unregisterManagers(){
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterManagers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterManagers();
    }
}
