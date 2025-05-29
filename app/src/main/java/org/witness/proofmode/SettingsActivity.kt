package org.witness.proofmode

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.AccountPicker.AccountChooserOptions
import org.witness.proofmode.PermissionActivity
import org.witness.proofmode.PermissionActivity.Companion.hasPermissions
import org.witness.proofmode.ProofMode.PREF_CREDENTIALS_PRIMARY
import org.witness.proofmode.ProofMode.PREF_OPTION_AI_DEFAULT
import org.witness.proofmode.c2pa.C2paUtils
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.databinding.ActivitySettingsBinding
import org.witness.proofmode.storage.FilebaseConfig
import org.witness.proofmode.util.GPSTracker
import androidx.core.content.edit


class SettingsActivity : AppCompatActivity() {
    private lateinit var mPrefs: SharedPreferences
    private lateinit var switchLocation: CheckBox
    private lateinit var switchNetwork: CheckBox
    private lateinit var switchDevice: CheckBox
    private lateinit var switchNotarize: CheckBox
    private lateinit var switchCredentials: CheckBox
    private lateinit var switchAI: CheckBox
    private lateinit var switchAutoImport: CheckBox
    private lateinit var switchAutoSync: CheckBox

    private lateinit var binding:ActivitySettingsBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val title = binding.toolbarTitle
        title.text = getTitle()
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        switchLocation = binding.contentSettings.switchLocation
        switchNetwork = binding.contentSettings.switchNetwork
        switchDevice = binding.contentSettings.switchDevice
        switchNotarize = binding.contentSettings.switchNotarize
        switchCredentials = binding.contentSettings.switchCR
        switchAI = binding.contentSettings.switchAI
        switchAutoImport = binding.contentSettings.switchAutoImport
        switchAutoSync = binding.contentSettings.switchAutoSync


        
        updateUI()
        switchLocation.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            if (isChecked) {
                if (!askForPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        REQUEST_CODE_LOCATION,
                        R.layout.permission_location
                    )
                ) {
                    /**
                    if (!askForPermission(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                            0,
                            REQUEST_CODE_LOCATION_BACKGROUND,
                            0
                        )
                    ) {
                        mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
                        refreshLocation()
                    }*/
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
                    refreshLocation()
                }
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()
            }
            updateUI()
        }
        switchNetwork.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            if (isChecked) {
                if (!askForPermission(
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        REQUEST_CODE_NETWORK_STATE,
                        0
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit()
                }
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, false).commit()
            }
            updateUI()
        }
        switchDevice.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            if (isChecked) {
                if (!askForPermission(
                        Manifest.permission.READ_PHONE_STATE,
                        REQUEST_CODE_READ_PHONE_STATE,
                        0
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit()
                }
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, false).commit()
            }
            updateUI()
        }
        switchNotarize.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NOTARY, isChecked)
                .commit()
            updateUI()
        }

        switchCredentials.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_CREDENTIALS, isChecked)
                .commit()

            if (!isChecked)
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_AI, PREF_OPTION_AI_DEFAULT)
                    .commit()

            switchAI.isEnabled = isChecked

            if (isChecked)
                showIdentityChooser()

            updateUI()
        }

        switchAI.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_AI, isChecked)
                .commit()
            updateUI()
        }

        // Setup Filebase settings button
        switchAutoSync.setOnCheckedChangeListener {_: CompoundButton?, isChecked: Boolean ->

            mPrefs.edit().putBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, isChecked).commit()

            if (isChecked) {
                val intent = Intent(this, FilebaseSettingsActivity::class.java)
                startActivity(intent)
            }
            else {

            }


        }

        switchAutoImport.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->

            mPrefs.edit().putBoolean(ProofMode.PREFS_DOPROOF, isChecked).commit()

            if (isChecked)
                (application as ProofModeApp).init(this)
            else
                (application as ProofModeApp).cancel(this)


        }

    }

    private val REQ_ACCOUNT_CHOOSER = 9999;

    private fun showIdentityChooser () {

        val intent = AccountPicker.newChooseAccountIntent(
            AccountChooserOptions.Builder()
                .build()
        )

        startActivityForResult(intent, REQ_ACCOUNT_CHOOSER)
    }

    private fun updateUI() {
        switchLocation.isChecked =
            mPrefs.getBoolean(
                ProofMode.PREF_OPTION_LOCATION,
                ProofMode.PREF_OPTION_LOCATION_DEFAULT
            )
        switchNetwork.isChecked =
            mPrefs.getBoolean(
                ProofMode.PREF_OPTION_NETWORK,
                ProofMode.PREF_OPTION_NETWORK_DEFAULT
            )
        switchDevice.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT)
        switchNotarize.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT)

        switchCredentials.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_CREDENTIALS, ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT)


        switchAI.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_AI, ProofMode.PREF_OPTION_AI_DEFAULT)

        switchAutoSync.isChecked =
            mPrefs.getBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, false)

        switchAutoImport.isChecked =
            mPrefs.getBoolean(ProofMode.PREFS_DOPROOF, false)

    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_LOCATION -> {
                if (hasPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
                    mPrefs.edit(commit = true) { putBoolean(ProofMode.PREF_OPTION_LOCATION, true) }
                    refreshLocation()
                }
                updateUI()
            }
            REQUEST_CODE_NETWORK_STATE -> {
                if (hasPermissions(this, arrayOf(Manifest.permission.ACCESS_NETWORK_STATE))) {
                    mPrefs.edit(commit = true) { putBoolean(ProofMode.PREF_OPTION_NETWORK, true) }
                }
                updateUI()
            }
            REQUEST_CODE_READ_PHONE_STATE -> {
                if (hasPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE))) {
                    mPrefs.edit(commit = true) { putBoolean(ProofMode.PREF_OPTION_PHONE, true) }
                }
                updateUI()
            }
            REQ_ACCOUNT_CHOOSER -> {
                val accountName = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);

                //only if the account is changed, should we change the credentials
                if (!mPrefs.getString(PREF_CREDENTIALS_PRIMARY,"").equals(accountName)) {
                    mPrefs.edit(commit = true) { putString(PREF_CREDENTIALS_PRIMARY, accountName) }
                    initContentCredentials(accountName)
                }

            }

        }
    }

    fun initContentCredentials (accountName : String?) {

        val mPgpUtils = PgpUtils.getInstance();

        val email = accountName;
        var display : String? = null
        val key : String? = "0x" + mPgpUtils?.publicKeyFingerprint
        var uri : String? =  "https://keys.openpgp.org/search?q=" + mPgpUtils?.publicKeyFingerprint

        if (email?.isNotEmpty() == true)
        {
            display = "${email.replace("@"," at ")}"
            uri = "mailto://$email"
        }

        C2paUtils.setC2PAIdentity(display, uri, email, key)
        if (email != null && key != null) {
            C2paUtils.backupCredentials(this)
            C2paUtils.resetCredentials(this)
            C2paUtils.initCredentials(this, email, key)
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        if (id == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun askForPermission(permission: String, requestCode: Int, layoutId: Int): Boolean {
        val permissions = arrayOf(permission)
        if (!hasPermissions(this, permissions)) {
            val intent = Intent(this, PermissionActivity::class.java)
            intent.putExtra(PermissionActivity.ARG_PERMISSIONS, permissions)
            if (layoutId != 0) {
                intent.putExtra(PermissionActivity.ARG_LAYOUT_ID, R.layout.permission_location)
            }
            startActivityForResult(intent, requestCode)
            return true
        }
        return false
    }

    private fun refreshLocation() {
        val gpsTracker = GPSTracker(this)
        if (gpsTracker.canGetLocation()) {
            gpsTracker.location
        }
    }

    companion object {
        private const val REQUEST_CODE_LOCATION = 1
        private const val REQUEST_CODE_NETWORK_STATE = 2
        private const val REQUEST_CODE_READ_PHONE_STATE = 3
       // private const val REQUEST_CODE_LOCATION_BACKGROUND = 4
    }
}