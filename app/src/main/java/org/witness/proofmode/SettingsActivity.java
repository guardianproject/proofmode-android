package org.witness.proofmode;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.util.GPSTracker;

public class SettingsActivity extends AppCompatActivity {

    private final static int REQUEST_CODE_LOCATION = 1;
    private final static int REQUEST_CODE_NETWORK_STATE = 2;
    private final static int REQUEST_CODE_READ_PHONE_STATE = 3;

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
                if (isChecked)
                {
                    if (!askForPermission(Manifest.permission.ACCESS_FINE_LOCATION, REQUEST_CODE_LOCATION)) {
                        mPrefs.edit().putBoolean("trackLocation",isChecked).commit();
                        refreshLocation();
                    }
                } else {
                    mPrefs.edit().putBoolean("trackLocation",isChecked).commit();
                }
                updateUI();
            }
        });

        switchMobile.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked)
                {
                    if (!askForPermission(Manifest.permission.ACCESS_NETWORK_STATE, REQUEST_CODE_NETWORK_STATE)) {
                        mPrefs.edit().putBoolean("trackMobileNetwork",isChecked).commit();
                    }
                } else {
                    mPrefs.edit().putBoolean("trackMobileNetwork",isChecked).commit();
                }
                updateUI();
            }
        });

        switchDevice.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked)
                {
                    if (!askForPermission(Manifest.permission.READ_PHONE_STATE, REQUEST_CODE_READ_PHONE_STATE)) {
                        mPrefs.edit().putBoolean("trackDeviceId",isChecked).commit();
                    }
                } else {
                    mPrefs.edit().putBoolean("trackDeviceId",isChecked).commit();
                }
                updateUI();
            }
        });

        switchNotarize.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mPrefs.edit().putBoolean("autoNotarize", switchNotarize.isChecked()).commit();
                updateUI();
            }
        });
    }

    private void updateUI() {
        switchLocation.setChecked(mPrefs.getBoolean("trackLocation",false));
        switchMobile.setChecked(mPrefs.getBoolean("trackMobileNetwork",false));
        switchDevice.setChecked(mPrefs.getBoolean("trackDeviceId",false));
        switchNotarize.setChecked(mPrefs.getBoolean("autoNotarize",false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            //Location
            case REQUEST_CODE_LOCATION:
                if (PermissionActivity.hasPermissions(this, new String[] { Manifest.permission.ACCESS_FINE_LOCATION })) {
                    mPrefs.edit().putBoolean("trackLocation", true).commit();
                    refreshLocation();
                }
                updateUI();
                break;
            case REQUEST_CODE_NETWORK_STATE:
                if (PermissionActivity.hasPermissions(this, new String[] { Manifest.permission.ACCESS_NETWORK_STATE })) {
                    mPrefs.edit().putBoolean("trackMobileNetwork", true).commit();
                }
                updateUI();
                break;

            case REQUEST_CODE_READ_PHONE_STATE:
                if (PermissionActivity.hasPermissions(this, new String[] { Manifest.permission.READ_PHONE_STATE })) {
                    mPrefs.edit().putBoolean("trackDeviceId", true).commit();
                }
                updateUI();
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
