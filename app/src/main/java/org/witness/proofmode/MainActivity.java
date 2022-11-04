package org.witness.proofmode;

import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_SEND_MULTIPLE;
import static org.witness.proofmode.ProofMode.PREFS_DOPROOF;

import android.Manifest;
import android.animation.Animator;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import org.jetbrains.annotations.NotNull;
import org.witness.proofmode.camera.CameraModuleActivity;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.onboarding.OnboardingActivity;
import org.witness.proofmode.util.GPSTracker;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import gun0912.tedimagepicker.builder.TedImagePicker;
import gun0912.tedimagepicker.builder.listener.OnMultiSelectedListener;
import gun0912.tedimagepicker.builder.listener.OnSelectedListener;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private SharedPreferences mPrefs;

    private final static int REQUEST_CODE_INTRO = 9999;
    private final static int REQUEST_CODE_REQUIRED_PERMISSIONS = 9998;
    private final static int REQUEST_CODE_OPTIONAL_PERMISSIONS = 9997;
    private final static int REQUEST_CODE_CHOOSE_MEDIA = 9996;



    private PgpUtils mPgpUtils;
    private View layoutOn;
    private View layoutOff;
    private DrawerLayout drawer;
    private ActionBarDrawerToggle drawerToggle;

    /**
     * The permissions needed for "base" ProofMode to work, without extra options.
     */
    private final static String[] requiredPermissions = new String[] {
            Manifest.permission.READ_EXTERNAL_STORAGE,
    };
    private final static String[] optionalPermissions = new String[] {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
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
        btnSettings.setOnClickListener(v -> openSettings());

        View btnShareProof = findViewById(R.id.btnShareProof);
        btnShareProof.setOnClickListener(v -> {

            // Initializing the popup menu and giving the reference as current context
            PopupMenu popupMenu = new PopupMenu(MainActivity.this, v);

            // Inflating popup menu from popup_menu.xml file
            popupMenu.getMenuInflater().inflate(R.menu.menu_share_proof, popupMenu.getMenu());
            popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {

                    if (menuItem.getItemId()==R.id.menu_photo)
                        showImagePicker();
                    else if (menuItem.getItemId()==R.id.menu_video)
                        showVideoPicker();
                    else if (menuItem.getItemId()==R.id.menu_files)
                        showDocumentPicker();

                    return true;
                }
            });
            // Showing the popup menu
            popupMenu.show();




        });

        updateOnOffState(false);
    }

    private void showDocumentPicker () {
        Intent intent = new Intent();
        intent.setType("*/*");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent,getString(R.string.share_proof_action)), REQUEST_CODE_CHOOSE_MEDIA);

    }

    private void showImagePicker () {
        TedImagePicker.with(this).image().dropDownAlbum()
                .startMultiImage(mediaList -> {
                    showShareProof(mediaList);
                });
    }

    private void showVideoPicker () {
        TedImagePicker.with(this).video().showVideoDuration(true).dropDownAlbum()
                .startMultiImage(mediaList -> {
                    showShareProof(mediaList);
                });
    }

    private void showShareProof (List<? extends Uri> mediaList) {
        Intent intentShare = new Intent(this,ShareProofActivity.class);
        intentShare.setAction(ACTION_SEND_MULTIPLE);

        ArrayList<Uri> aList = new ArrayList<>();
        for (Uri uri : mediaList)
            aList.add(uri);

        intentShare.putParcelableArrayListExtra(Intent.EXTRA_STREAM,aList);
        startActivity(intentShare);
    }

    public void toggleOnClicked(View view) {
        setProofModeOn(true);
    }

    public void toggleOffClicked(View view) {
        setProofModeOn(false);
    }

    private void setProofModeOn(boolean isOn) {
        if (isOn)
        {
            if (!askForPermissions(requiredPermissions, REQUEST_CODE_REQUIRED_PERMISSIONS)) {
                mPrefs.edit().putBoolean(PREFS_DOPROOF, isOn).commit();
                updateOnOffState(true);
                ((ProofModeApp)getApplication()).init(this);
            }
        } else {
            mPrefs.edit().putBoolean(PREFS_DOPROOF, isOn).commit();
            updateOnOffState(true);
            ((ProofModeApp)getApplication()).cancel(this);
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

    private void pickMedia () {


        // create an instance of the
        // intent of the type image
        Intent i = new Intent();
        i.setType("image/*");
        i.setAction(Intent.ACTION_GET_CONTENT);

        // pass the constant to compare it
        // with the returned requestCode
        startActivityForResult(Intent.createChooser(i, "Open Picture"), 9999);

        /**
        Matisse.from(MainActivity.this)
                .choose(MimeType.ofAll())
                .countable(true)
             //   .maxSelectable(9)
           //     .gridExpectedSize(getResources().getDimensionPixelSize(R.dimen.grid_expected_size))
                .restrictOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                .thumbnailScale(0.85f)
                .showPreview(false) // Default is `true`
                .forResult(9999);
         **/

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
            Toast.makeText(this, getString(R.string.publish_key_to) + PgpUtils.URL_LOOKUP_ENDPOINT, Toast.LENGTH_LONG).show();

            //String fingerprint = mPgpUtils.getPublicKeyFingerprint();

            //Toast.makeText(this, R.string.open_public_key_page, Toast.LENGTH_LONG).show();

            //openUrl(PgpUtils.URL_LOOKUP_ENDPOINT + fingerprint);

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

            Intent intent = new Intent(ACTION_SEND);
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
                askForOptionals();
            }
        } else if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            // We call with REQUEST_CODE_REQUIRED_PERMISSIONS to turn ProofMode on, so set it to on if we have the permissions
            if (PermissionActivity.hasPermissions(this, requiredPermissions)) {
                setProofModeOn(true);
                askForOptionals();
            } else {
                setProofModeOn(false);
            }
        } else if (requestCode == REQUEST_CODE_OPTIONAL_PERMISSIONS) {
            if (PermissionActivity.hasPermissions(this, new String[] { Manifest.permission.ACCESS_NETWORK_STATE })) {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit();
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, false).commit();
            }
            if (PermissionActivity.hasPermissions(this, new String[] { Manifest.permission.READ_PHONE_STATE })) {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit();
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, false).commit();
            }
        } else if (requestCode == REQUEST_CODE_CHOOSE_MEDIA) {
            Intent intentShare = new Intent(this,ShareProofActivity.class);
            intentShare.setType(data.getType());

            if (data.getData() != null) {
                intentShare.setAction(ACTION_SEND);
                intentShare.setData(data.getData());
            }
            if (data.getClipData() != null) {
                intentShare.setAction(ACTION_SEND_MULTIPLE);
                intentShare.setClipData(data.getClipData());
            }
            startActivity(intentShare);

        }
    }


    private void askForOptionals() {
        if (!askForPermissions(optionalPermissions, REQUEST_CODE_OPTIONAL_PERMISSIONS)) {
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit();
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit();
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

    private void openDataLegend() {
        Intent intent = new Intent(MainActivity.this, DataLegendActivity.class);
        startActivity(intent);
    }

    private void openDigitalSignatures() {
        Intent intent = new Intent(MainActivity.this, DigitalSignaturesActivity.class);
        startActivity(intent);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            //case R.id.menu_home:
            //    drawer.closeDrawer(Gravity.START);
            //    return true;
            case R.id.menu_how_it_works:
                drawer.closeDrawer(GravityCompat.START);
                Intent intent = new Intent(this, OnboardingActivity.class);
                intent.putExtra(OnboardingActivity.ARG_ONLY_TUTORIAL, true);
                startActivityForResult(intent, REQUEST_CODE_INTRO);
                return true;
            case R.id.menu_settings:
                drawer.closeDrawer(GravityCompat.START);
                openSettings();
                return true;
            case R.id.menu_datalegend:
                drawer.closeDrawer(GravityCompat.START);
                openDataLegend();
                return true;
            case R.id.menu_digital_signatures:
                drawer.closeDrawer(GravityCompat.START);
                openDigitalSignatures();
                return true;

        }
        return false;
    }

    public void startCamera (View view) {

        startActivity(new Intent(this, CameraModuleActivity.class));
    }

}
