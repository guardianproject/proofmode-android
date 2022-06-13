package org.witness.proofmode

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.preference.PreferenceManager
import org.witness.proofmode.databinding.ActivitySettingsBinding
import org.witness.proofmode.util.GPSTracker

class SettingsActivity : AppCompatActivity() {
    private lateinit var mPrefs: SharedPreferences
    private lateinit var switchLocation: CheckBox
    private lateinit var switchMobile: CheckBox
    private lateinit var switchDevice: CheckBox
    private lateinit var switchNotarize: CheckBox
    private lateinit var binding:ActivitySettingsBinding

    private fun initViews() {
        switchLocation = binding.contentSettings.switchLocation
        switchMobile = binding.contentSettings.switchCellInfo
        switchDevice = binding.contentSettings.switchDevice
        switchNotarize = binding.contentSettings.switchNotarize
    }

    private fun setUpCheckboxListeners() {
        switchLocation.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!askForPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        REQUEST_CODE_LOCATION,
                        R.layout.permission_location
                    )
                ) {
                    if (!askForPermission(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                            REQUEST_CODE_LOCATION_BACKGROUND,
                            0
                        )
                    ) {
                        mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, isChecked).commit()
                        refreshLocation()
                    }
                }
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, isChecked).commit()
            }
            updateUI()
        }
        switchMobile.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!askForPermission(
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        REQUEST_CODE_NETWORK_STATE,
                        0
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, isChecked).commit()
                }
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, isChecked).commit()
            }
            updateUI()
        }
        switchDevice.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!askForPermission(
                        Manifest.permission.READ_PHONE_STATE,
                        REQUEST_CODE_READ_PHONE_STATE,
                        0
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, isChecked).commit()
                }
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, isChecked).commit()
            }
            updateUI()
        }
        switchNotarize.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NOTARY, switchNotarize.isChecked)
                .commit()
            updateUI()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this,R.layout.activity_settings)
        binding.lifecycleOwner = this
        val toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        supportActionBar!!.setDisplayShowHomeEnabled(true)
        supportActionBar!!.setDisplayShowTitleEnabled(false)
        val title = binding.toolbarTitle
        title.text = getTitle()
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        initViews()
        updateUI()
        setUpCheckboxListeners()
    }

    private fun updateUI() {
        switchLocation.isChecked =
            mPrefs.getBoolean(
                ProofMode.PREF_OPTION_LOCATION,
                ProofMode.PREF_OPTION_LOCATION_DEFAULT
            )
        switchMobile.isChecked =
            mPrefs.getBoolean(
                ProofMode.PREF_OPTION_NETWORK,
                ProofMode.PREF_OPTION_NETWORK_DEFAULT
            )
        switchDevice.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT)
        switchNotarize.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_LOCATION -> {
                if (PermissionActivity.hasPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    )
                ) {
                    askForPermission(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        REQUEST_CODE_LOCATION_BACKGROUND,
                        0
                    )
                }
                updateUI()
            }
            REQUEST_CODE_LOCATION_BACKGROUND -> {
                if (PermissionActivity.hasPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
                    refreshLocation()
                }
                updateUI()
            }
            REQUEST_CODE_NETWORK_STATE -> {
                if (PermissionActivity.hasPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_NETWORK_STATE)
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit()
                }
                updateUI()
            }
            REQUEST_CODE_READ_PHONE_STATE -> {
                if (PermissionActivity.hasPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_PHONE_STATE)
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit()
                }
                updateUI()
            }
        }
    }

    override fun onBackPressed() {
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
        if (!PermissionActivity.hasPermissions(this, permissions)) {
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
        private const val REQUEST_CODE_LOCATION_BACKGROUND = 4
    }
}