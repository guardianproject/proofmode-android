package org.witness.proofmode;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.util.GPSTracker;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences mPrefs;

    private PgpUtils mPgpUtils;
    private CheckBox switchLocation;
    private CheckBox switchMobile;
    private CheckBox switchDevice;
    private CheckBox switchNotarize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        switchLocation = (CheckBox)findViewById(R.id.switchLocation);
        switchMobile = (CheckBox)findViewById(R.id.switchCellInfo);
        switchDevice = (CheckBox)findViewById(R.id.switchDevice);
        switchNotarize = (CheckBox) findViewById(R.id.switchNotarize);
        updateUI();

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

        switchDevice.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

                mPrefs.edit().putBoolean("trackDeviceId",isChecked).commit();

            }
        });

        switchNotarize.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mPrefs.edit().putBoolean("autoNotarize", switchNotarize.isChecked()).commit();
            }
        });
    }

    private void updateUI() {
        switchLocation.setChecked(mPrefs.getBoolean("trackLocation",true));
        switchMobile.setChecked(mPrefs.getBoolean("trackMobileNetwork",false));
        switchDevice.setChecked(mPrefs.getBoolean("trackDeviceId",true));
        switchNotarize.setChecked(mPrefs.getBoolean("autoNotarize",true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
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

    private void refreshLocation ()
    {
        GPSTracker gpsTracker = new GPSTracker(this);
        if (gpsTracker.canGetLocation()) {
            gpsTracker.getLocation();
        }
    }
}
