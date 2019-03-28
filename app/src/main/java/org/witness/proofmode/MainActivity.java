package org.witness.proofmode;

import android.Manifest;
import android.animation.Animator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.NavigationView;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.onboarding.OnboardingActivity;
import org.witness.proofmode.util.GPSTracker;

import java.io.IOException;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private SharedPreferences mPrefs;

    private final static int REQUEST_CODE_INTRO = 9999;
    private final static int REQUEST_CODE_REQUIRED_PERMISSIONS = 9998;

    private PgpUtils mPgpUtils;
    private View layoutOn;
    private View layoutOff;
    private DrawerLayout drawer;
    private ActionBarDrawerToggle drawerToggle;

    /**
     * The permissions needed for "base" ProofMode to work, without extra options.
     */
    private final static String[] requiredPermissions = new String[] {
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle("");

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        View rootView = findViewById(R.id.root);
        layoutOn = rootView.findViewById(R.id.layout_on);
        layoutOff = rootView.findViewById(R.id.layout_off);
        layoutOn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                setProofModeOn(false);
                return true;
            }
        });
        layoutOff.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                setProofModeOn(true);
                return true;
            }
        });

        if (mPrefs.getBoolean("firsttime",true)) {
            startActivityForResult(new Intent(this, OnboardingActivity.class), REQUEST_CODE_INTRO);
        }

        //Setup drawer
        drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawerToggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, 0, 0);
        drawer.addDrawerListener(drawerToggle);
        drawerToggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });

        updateOnOffState(false);
    }

    private void setProofModeOn(boolean isOn) {
        if (isOn)
        {
            if (!askForPermissions(requiredPermissions, REQUEST_CODE_REQUIRED_PERMISSIONS)) {
                mPrefs.edit().putBoolean("doProof", isOn).commit();
                updateOnOffState(true);
            }
        } else {
            mPrefs.edit().putBoolean("doProof", isOn).commit();
            updateOnOffState(true);
        }
    }

    private void updateOnOffState(boolean animate) {
        final boolean isOn = mPrefs.getBoolean("doProof",false);
        if (animate) {
            layoutOn.animate().alpha(isOn ? 1.0f : 0.0f).setDuration(300).setListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    if (isOn) {
                        layoutOn.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!isOn) {
                        layoutOn.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {

                }

                @Override
                public void onAnimationRepeat(Animator animation) {

                }
            }).start();
            layoutOff.animate().alpha(isOn ? 0.0f : 1.0f).setDuration(300).setListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    if (!isOn) {
                        layoutOff.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (isOn) {
                        layoutOff.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {

                }

                @Override
                public void onAnimationRepeat(Animator animation) {

                }
            }).start();
        } else {
            layoutOn.setAlpha(isOn ? 1.0f : 0.0f);
            layoutOn.setVisibility(isOn ? View.VISIBLE : View.GONE);
            layoutOff.setAlpha(isOn ? 0.0f : 1.0f);
            layoutOff.setVisibility(isOn ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateOnOffState(false);
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
        if (id == R.id.action_publish_key){

            publishKey();

            return true;
        }
        else if (id == R.id.action_share_key){

            shareKey();

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * User the PermissionActivity to ask for permissions, but show no UI when calling from here.
     */
    private boolean askForPermissions(String[] permissions, Integer requestCode) {
        if (!PermissionActivity.hasPermissions(this, permissions)) {
            Intent intent = new Intent(this, PermissionActivity.class);
            intent.putExtra(PermissionActivity.ARG_PERMISSIONS, permissions);
            startActivityForResult(intent, requestCode);
            return true;
        }
        return false;
    }

    private boolean askForPermission(String permission, Integer requestCode) {
        return askForPermissions(new String[] { permission }, requestCode);
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
        if (requestCode == REQUEST_CODE_INTRO)
        {
            mPrefs.edit().putBoolean("firsttime",false).commit();

            // Ask for initial permissions
            if (!askForPermissions(requiredPermissions, REQUEST_CODE_REQUIRED_PERMISSIONS)) {
                // We have permission
                setProofModeOn(true);
            }
        } else if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            // We call with REQUEST_CODE_REQUIRED_PERMISSIONS to turn ProofMode on, so set it to on if we have the permissions
            if (PermissionActivity.hasPermissions(this, requiredPermissions)) {
                setProofModeOn(true);
            } else {
                setProofModeOn(false);
            }
        }
    }

    private void refreshLocation ()
    {
        GPSTracker gpsTracker = new GPSTracker(this);
        if (gpsTracker.canGetLocation()) {
            gpsTracker.getLocation();
        }
    }

    private void openSettings() {
        Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
        startActivity(intent);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.menu_home:
                drawer.closeDrawer(Gravity.START);
                return true;
            case R.id.menu_how_it_works:
                drawer.closeDrawer(Gravity.START);
                startActivityForResult(new Intent(this, OnboardingActivity.class),REQUEST_CODE_INTRO);
                return true;
            case R.id.menu_settings:
                drawer.closeDrawer(Gravity.START);
                openSettings();
                return true;
        }
        return false;
    }
}
