package org.witness.proofmode

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.preference.PreferenceManager
import org.witness.proofmode.PermissionActivity.Companion.hasPermissions
import org.witness.proofmode.ProofMode.PREF_CREDENTIALS_PRIMARY
import org.witness.proofmode.databinding.ActivitySettingsBinding
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.share.FilebaseSettingsActivity
import org.witness.proofmode.storage.filebase.FilebaseConfig


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
    private lateinit var versionText: TextView
    private lateinit var developerPreviewEntry: TextView

    private var pendingEnableLocation = false
    private var requestAttemptedThisCycle = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantMap ->
        handleLocationPermissionResult(grantMap)
    }

    private lateinit var binding:ActivitySettingsBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            pendingEnableLocation =
                savedInstanceState.getBoolean(KEY_PENDING_ENABLE_LOCATION, false)
            requestAttemptedThisCycle =
                savedInstanceState.getBoolean(KEY_REQUEST_ATTEMPTED, false)
        }

        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setTitle("")
        binding.toolbar.setTitleTextColor(getColor(R.color.colorPrimaryDark))
        binding.toolbar.setNavigationIconTint(getColor(R.color.colorPrimaryDark))

        //supportActionBar?.setDisplayShowTitleEnabled(false)

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        switchLocation = binding.contentSettings.switchLocation
        switchNetwork = binding.contentSettings.switchNetwork
        switchDevice = binding.contentSettings.switchDevice
        switchNotarize = binding.contentSettings.switchNotarize
        switchCredentials = binding.contentSettings.switchCR
        switchAI = binding.contentSettings.switchAI
        switchAutoImport = binding.contentSettings.switchAutoImport
        switchAutoSync = binding.contentSettings.switchAutoSync
        versionText = binding.contentSettings.textVersion
        developerPreviewEntry = binding.contentSettings.textDeveloperPreview

        versionText.text = getString(R.string.settings_version_format, BuildConfig.VERSION_NAME)

        developerPreviewEntry.setOnClickListener {
            startActivity(Intent(this, DeveloperPreviewActivity::class.java))
        }

        updateUI()
        updateDeveloperPreviewVisibility()

        // Location cell opens dedicated Location settings; checkbox is read-only indicator.
        binding.contentSettings.cellLocation.setOnClickListener {
            startActivity(Intent(this, LocationSettingsActivity::class.java))
        }
        binding.contentSettings.cellLocation.setOnLongClickListener {
            val currentlyOn = mPrefs.getBoolean(
                ProofMode.PREF_OPTION_LOCATION,
                ProofMode.PREF_OPTION_LOCATION_DEFAULT,
            )
            if (currentlyOn) {
                val snap = LocationSharingPermissionSync.beginDisable(this)
                pendingEnableLocation = snap.pendingEnable
                updateUI()
            } else {
                val plan = LocationSharingPermissionSync.beginEnable(this)
                if (plan.needsPermissionRequest) {
                    pendingEnableLocation = true
                    requestAttemptedThisCycle = true
                    locationPermissionLauncher.launch(
                        LocationSharingPermissionSync.LOCATION_PERMISSIONS,
                    )
                }
                updateUI()
            }
            true
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

        // The Notary cell opens the dedicated Notary settings screen (master enable +
        // per-provider toggles + Nostr identity); the checkbox is a read-only indicator.
        binding.contentSettings.cellNotary.setOnClickListener {
            startActivity(Intent(this, NotarySettingsActivity::class.java))
        }

        // The Credentials cell is not a toggle: tapping it always opens the signing
        // settings, where the user picks Remote / Local / Disabled. The checkbox is a
        // read-only indicator of that mode (off only when signing is Disabled).
        binding.contentSettings.cellCredentials.setOnClickListener {
            startActivity(Intent(this, SigningSettingsActivity::class.java))
        }

        switchAI.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_BLOCK_AI, isChecked)
                .commit()
            updateUI()
        }


        // Auto Sync cell opens Filebase settings on tap; long-press toggles auto-upload
        // when configured. The checkbox is a read-only indicator.
        binding.contentSettings.cellAutoSync.setOnClickListener {
            startActivity(Intent(this, FilebaseSettingsActivity::class.java))
        }

        binding.contentSettings.cellAutoSync.setOnLongClickListener {
            if (FilebaseConfig.toggleAutoUploadIfConfigured(mPrefs)) {
                updateUI()
                MediaWatcher.getInstance(this)?.refreshStorageProvider(null)
            } else {
                startActivity(Intent(this, FilebaseSettingsActivity::class.java))
            }
            true
        }

        switchAutoImport.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->

            /**
            mPrefs.edit().putBoolean(ProofMode.PREFS_DOPROOF, isChecked).commit()

            if (isChecked)
                (application as ProofModeApp).init(this)
            else
                (application as ProofModeApp).cancel(this)
            **/
            //Toast.makeText(this, getString(R.string.coming_soon),Toast.LENGTH_LONG).show()


        }


    }

    private val REQ_ACCOUNT_CHOOSER = 9999;

    private fun showIdentityChooser () {

        /**
        val intent = AccountPicker.newChooseAccountIntent(
            AccountPicker.AccountChooserOptions.Builder()
                .build()
        )

        startActivityForResult(intent, REQ_ACCOUNT_CHOOSER)
        **/
    }



    private fun updateUI() {
        switchLocation.isChecked =
            mPrefs.getBoolean(
                ProofMode.PREF_OPTION_LOCATION,
                ProofMode.PREF_OPTION_LOCATION_DEFAULT
            )

        updateLocationDesc()

        switchNetwork.isChecked =
            mPrefs.getBoolean(
                ProofMode.PREF_OPTION_NETWORK,
                ProofMode.PREF_OPTION_NETWORK_DEFAULT
            )
        switchDevice.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT)
        switchNotarize.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT)

        val credentialsEnabled =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_CREDENTIALS, ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT)
        switchCredentials.isChecked = credentialsEnabled

        switchAI.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_BLOCK_AI, ProofMode.PREF_OPTION_AI_DEFAULT)

        switchAI.isEnabled = credentialsEnabled

        val filebaseCfg = FilebaseConfig.fromPrefs(mPrefs)
        switchAutoSync.isChecked = FilebaseConfig.autoSyncIndicatorChecked(filebaseCfg)

        switchAutoImport.isChecked =
            mPrefs.getBoolean(ProofMode.PREFS_DOPROOF, false)

        //disable auto import for now
        switchAutoImport.isEnabled = false

        updateCredentialsDesc()
    }

    private fun updateLocationDesc () {

        val textLocationDesc = binding.contentSettings.textLocationDesc

        val hasFineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocation) {
            // Precise location access (GPS)
            textLocationDesc.text = getString(R.string.settings_location_desc)
        } else if (hasCoarseLocation) {
            // Only approximate location access
            textLocationDesc.text = getString(R.string.settings_location_desc_approx)
        } else {
            // No location access

            textLocationDesc.text = getString(R.string.settings_location_desc_none)
        }
    }

    private fun updateCredentialsDesc() {
        val textCRDesc = binding.contentSettings.textCRDesc
        val credentialsEnabled = mPrefs.getBoolean(
            ProofMode.PREF_OPTION_CREDENTIALS,
            ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT
        )
        val isRemote = mPrefs.getBoolean(
            ProofMode.PREF_OPTION_REMOTE_SIGNING,
            ProofMode.PREF_OPTION_REMOTE_SIGNING_DEFAULT
        )
        textCRDesc.text = when {
            !credentialsEnabled -> getString(R.string.settings_credentials_disabled)
            isRemote -> getString(R.string.settings_credentials_remote)
            else -> getString(R.string.settings_credentials_local)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PENDING_ENABLE_LOCATION, pendingEnableLocation)
        outState.putBoolean(KEY_REQUEST_ATTEMPTED, requestAttemptedThisCycle)
    }

    override fun onResume() {
        super.onResume()
        val snap = LocationSharingPermissionSync.reconcileOnResume(this, pendingEnableLocation)
        pendingEnableLocation = snap.pendingEnable
        updateUI()
        updateDeveloperPreviewVisibility()
    }

    private fun handleLocationPermissionResult(grantMap: Map<String, Boolean>) {
        val result = LocationSharingPermissionSync.onPermissionLauncherResult(
            activity = this,
            context = this,
            pendingEnable = pendingEnableLocation,
            requestAttempted = requestAttemptedThisCycle,
            grantMap = grantMap,
        )
        pendingEnableLocation = result.pendingEnable
        requestAttemptedThisCycle = false
        if (result.openAppInfo) {
            startActivity(LocationSharingPermissionSync.applicationDetailsIntent(packageName))
        }
        updateUI()
    }

    /** Package-visible for Robolectric T6 long-press permission coverage. */
    internal fun handleLocationPermissionResultForTests(grantMap: Map<String, Boolean>) {
        handleLocationPermissionResult(grantMap)
    }

    internal fun pendingEnableLocationForTests(): Boolean = pendingEnableLocation

    private fun updateDeveloperPreviewVisibility() {
        developerPreviewEntry.visibility = View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
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
                }

            }

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

    companion object {
        private const val REQUEST_CODE_NETWORK_STATE = 2
        private const val REQUEST_CODE_READ_PHONE_STATE = 3
       // private const val REQUEST_CODE_LOCATION_BACKGROUND = 4
        internal const val KEY_PENDING_ENABLE_LOCATION = "pendingEnableLocation"
        internal const val KEY_REQUEST_ATTEMPTED = "requestAttemptedThisCycle"
    }
}
