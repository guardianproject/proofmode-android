package org.witness.proofmode

import android.Manifest
import android.animation.Animator
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationView
import org.witness.proofmode.crypto.PgpUtils
import org.witness.proofmode.databinding.ActivityMainBinding
import org.witness.proofmode.onboarding.OnboardingActivity
import org.witness.proofmode.util.GPSTracker
import org.witness.proofmode.viewmodels.MainViewModel
import org.witness.proofmode.viewmodels.MainViewModelFactory
import java.io.IOException

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var mPrefs: SharedPreferences
    private lateinit var mainViewModel: MainViewModel
    private lateinit var mPgpUtils: PgpUtils
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var mainBinding: ActivityMainBinding
    private fun createViewModel() {
        val viewModelFactory = MainViewModelFactory(application)
        mainViewModel = ViewModelProvider(this, viewModelFactory).get(
            MainViewModel::class.java
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainBinding = DataBindingUtil.setContentView(this,R.layout.activity_main)
        createViewModel()
        mainBinding.mainViewModel = mainViewModel
        mainBinding.lifecycleOwner = this
        setSupportActionBar(mainBinding.toolbar)
        mainBinding.toolbar.title = ""
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        mPgpUtils = PgpUtils.getInstance(this,mPrefs.getString("password", PgpUtils.DEFAULT_PASSWORD))
        if (mPrefs.getBoolean("firsttime", true)) {
            startActivityForResult(Intent(this, OnboardingActivity::class.java), REQUEST_CODE_INTRO)
        }

        //Setup drawer
        drawerToggle = ActionBarDrawerToggle(
            this, mainBinding.drawerLayout, mainBinding.toolbar, 0, 0
        )
        mainBinding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
        mainBinding.navView.setNavigationItemSelectedListener(this)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        btnSettings.setOnClickListener { v: View? -> openSettings() }
        val btnShareProof = findViewById<View>(R.id.btnShareProof)
        btnShareProof.setOnClickListener { v: View? ->
            val intent = Intent()
            intent.type = "image/*"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            intent.action = Intent.ACTION_GET_CONTENT
            startActivityForResult(
                Intent.createChooser(
                    intent,
                    getString(R.string.share_proof_action)
                ), REQUEST_CODE_CHOOSE_MEDIA
            )
        }
        updateOnOffState(false)
    }

    fun toggleOnClicked(view: View?) {
        setProofModeOn(true)
    }

    fun toggleOffClicked(view: View?) {
        setProofModeOn(false)
    }

    private fun setProofModeOn(isOn: Boolean) {
        if (isOn) {
            if (!askForPermissions(requiredPermissions, REQUEST_CODE_REQUIRED_PERMISSIONS)) {
                mPrefs.edit().putBoolean(ProofMode.PREFS_DOPROOF, isOn).commit()
                updateOnOffState(true)
                (application as ProofModeApp).init(this)
            }
        } else {
            mPrefs.edit().putBoolean(ProofMode.PREFS_DOPROOF, isOn).commit()
            updateOnOffState(true)
            (application as ProofModeApp).cancel(this)
        }
    }

    private fun updateOnOffState(animate: Boolean) {
        val layoutOn = mainBinding.contentMain.layoutOn
        val layoutOff = mainBinding.contentMain.layoutOff
        val isOn = mPrefs.getBoolean("doProof", false)
        if (animate) {
            layoutOn.animate().alpha(if (isOn) 1.0f else 0.0f).setDuration(300)
                .setListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {
                        if (isOn) {
                            layoutOn.visibility = View.VISIBLE
                        }
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!isOn) {
                            layoutOn.visibility = View.GONE
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {}
                    override fun onAnimationRepeat(animation: Animator) {}
                }).start()
            layoutOff.animate().alpha(if (isOn) 0.0f else 1.0f).setDuration(300)
                .setListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {
                        if (!isOn) {
                            layoutOff.visibility = View.VISIBLE
                        }
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (isOn) {
                            layoutOff.visibility = View.GONE
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {}
                    override fun onAnimationRepeat(animation: Animator) {}
                }).start()
        } else {
            layoutOn.alpha = if (isOn) 1.0f else 0.0f
            layoutOn.visibility = if (isOn) View.VISIBLE else View.GONE
            layoutOff.alpha = if (isOn) 0.0f else 1.0f
            layoutOff.visibility = if (isOn) View.GONE else View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        updateOnOffState(false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        if (id == R.id.action_publish_key) {
            publishKey()
            return true
        } else if (id == R.id.action_share_key) {
            shareKey()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * User the PermissionActivity to ask for permissions, but show no UI when calling from here.
     */
    private fun askForPermissions(permissions: Array<String>, requestCode: Int): Boolean {
        if (!PermissionActivity.hasPermissions(this, permissions)) {
            val intent = Intent(this, PermissionActivity::class.java)
            intent.putExtra(PermissionActivity.ARG_PERMISSIONS, permissions)
            startActivityForResult(intent, requestCode)
            return true
        }
        return false
    }

    private fun askForPermission(permission: String, requestCode: Int): Boolean {
        return askForPermissions(arrayOf(permission), requestCode)
    }

    private fun observeError() {
        mainViewModel.error.observe(this) {

        }
    }

    private fun publishKey() {
        try {
            mainViewModel.publishPublicKey()
            Toast.makeText(
                this,
                getString(R.string.publish_key_to) + PgpUtils.URL_LOOKUP_ENDPOINT,
                Toast.LENGTH_LONG
            ).show()

            //String fingerprint = mPgpUtils.getPublicKeyFingerprint();

            //Toast.makeText(this, R.string.open_public_key_page, Toast.LENGTH_LONG).show();

            //openUrl(PgpUtils.URL_LOOKUP_ENDPOINT + fingerprint);
        } catch (ioe: Exception) {
            Log.e("Proofmode", "error publishing key", ioe)
        }
    }

    private fun shareKey() {
        try {
            mainViewModel.publishPublicKey()
            val pubKey = mPgpUtils.publicKey
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, pubKey)
            startActivity(intent)
        } catch (ioe: IOException) {
            Log.e("Proofmode", "error publishing key", ioe)
        }
    }

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.data = Uri.parse(url)
        startActivity(i)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_INTRO) {
            mPrefs.edit().putBoolean("firsttime", false).commit()

            // Ask for initial permissions
            if (!askForPermissions(requiredPermissions, REQUEST_CODE_REQUIRED_PERMISSIONS)) {
                // We have permission
                setProofModeOn(true)
                askForOptionals()
            }
        } else if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            // We call with REQUEST_CODE_REQUIRED_PERMISSIONS to turn ProofMode on, so set it to on if we have the permissions
            if (PermissionActivity.hasPermissions(this, requiredPermissions)) {
                setProofModeOn(true)
                askForOptionals()
            } else {
                setProofModeOn(false)
            }
        } else if (requestCode == REQUEST_CODE_OPTIONAL_PERMISSIONS) {
            if (PermissionActivity.hasPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_NETWORK_STATE)
                )
            ) {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit()
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, false).commit()
            }
            if (PermissionActivity.hasPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_PHONE_STATE)
                )
            ) {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit()
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, false).commit()
            }
        } else if (requestCode == REQUEST_CODE_CHOOSE_MEDIA) {
            val intentShare = Intent(this, ShareProofActivity::class.java)
            intentShare.type = data!!.type
            if (data.data != null) {
                intentShare.action = Intent.ACTION_SEND
                intentShare.data = data.data
            }
            if (data.clipData != null) {
                intentShare.action = Intent.ACTION_SEND_MULTIPLE
                intentShare.clipData = data.clipData
            }
            startActivity(intentShare)
        }
    }

    private fun askForOptionals() {
        if (!askForPermissions(optionalPermissions, REQUEST_CODE_OPTIONAL_PERMISSIONS)) {
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit()
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit()
        }
    }

    private fun refreshLocation() {
        val gpsTracker = GPSTracker(this)
        if (gpsTracker.canGetLocation()) {
            gpsTracker.location
        }
    }

    private fun openSettings() {
        val intent = Intent(this@MainActivity, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun openDataLegend() {
        val intent = Intent(this@MainActivity, DataLegendActivity::class.java)
        startActivity(intent)
    }

    private fun openDigitalSignatures() {
        val intent = Intent(this@MainActivity, DigitalSignaturesActivity::class.java)
        startActivity(intent)
    }

    override fun onNavigationItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_how_it_works -> {
                mainBinding.drawerLayout.closeDrawer(GravityCompat.START)
                val intent = Intent(this, OnboardingActivity::class.java)
                intent.putExtra(OnboardingActivity.ARG_ONLY_TUTORIAL, true)
                startActivityForResult(intent, REQUEST_CODE_INTRO)
                return true
            }
            R.id.menu_settings -> {
                mainBinding.drawerLayout.closeDrawer(GravityCompat.START)
                openSettings()
                return true
            }
            R.id.menu_datalegend -> {
                mainBinding.drawerLayout.closeDrawer(GravityCompat.START)
                openDataLegend()
                return true
            }
            R.id.menu_digital_signatures -> {
                mainBinding.drawerLayout.closeDrawer(GravityCompat.START)
                openDigitalSignatures()
                return true
            }
        }
        return false
    }

    companion object {
        private const val REQUEST_CODE_INTRO = 9999
        private const val REQUEST_CODE_REQUIRED_PERMISSIONS = 9998
        private const val REQUEST_CODE_OPTIONAL_PERMISSIONS = 9997
        private const val REQUEST_CODE_CHOOSE_MEDIA = 9996

        /**
         * The permissions needed for "base" ProofMode to work, without extra options.
         */
        private val requiredPermissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        private val optionalPermissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CAMERA
        )
    }
}