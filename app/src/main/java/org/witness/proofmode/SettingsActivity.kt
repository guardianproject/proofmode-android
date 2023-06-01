package org.witness.proofmode

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.witness.proofmode.PermissionActivity
import org.witness.proofmode.PermissionActivity.Companion.hasPermissions
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.databinding.ActivitySettingsBinding
import org.witness.proofmode.util.GPSTracker

class SettingsActivity : AppCompatActivity() {
    private lateinit var mPrefs: SharedPreferences
    private val mPgpUtils: PgpUtils? = null
    private lateinit var switchLocation: CheckBox
    private lateinit var switchNetwork: CheckBox
    private lateinit var switchDevice: CheckBox
    private lateinit var switchNotarize: CheckBox
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
        updateUI()
        switchLocation.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
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
                        mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
                        refreshLocation()
                    }
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        askForPermission(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                            REQUEST_CODE_LOCATION_BACKGROUND,
                            0
                        )
                    }
                }
                updateUI()
            }
            REQUEST_CODE_LOCATION_BACKGROUND -> {
                if (hasPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
                    refreshLocation()
                }
                updateUI()
            }
            REQUEST_CODE_NETWORK_STATE -> {
                if (hasPermissions(this, arrayOf(Manifest.permission.ACCESS_NETWORK_STATE))) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit()
                }
                updateUI()
            }
            REQUEST_CODE_READ_PHONE_STATE -> {
                if (hasPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE))) {
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
        private const val REQUEST_CODE_LOCATION_BACKGROUND = 4
    }
}