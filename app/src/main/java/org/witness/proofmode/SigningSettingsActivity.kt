package org.witness.proofmode

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import androidx.preference.SwitchPreferenceCompat
import org.witness.proofmode.databinding.ActivitySettingsBinding
import org.witness.proofmode.databinding.ActivitySigningSettingsBinding

class SigningSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySigningSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySigningSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setTitleTextColor(getColor(R.color.colorPrimaryDark))
        binding.toolbar.setNavigationIconTint(getColor(R.color.colorPrimaryDark))
        supportActionBar?.title = getString(R.string.settings_signing_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_settings, SigningPreferencesFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    class SigningPreferencesFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = null
            setPreferencesFromResource(R.xml.signing_preferences, rootKey)
            applyModeState()
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key == ProofMode.PREF_OPTION_REMOTE_SIGNING) {
                applyModeState()
            }
        }

        private fun applyModeState() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            /**
            val isRemote = prefs.getBoolean(
                ProofMode.PREF_OPTION_REMOTE_SIGNING,
                ProofMode.PREF_OPTION_REMOTE_SIGNING_DEFAULT
            )**/

            val isRemote = findPreference<SwitchPreferenceCompat>(ProofMode.PREF_OPTION_REMOTE_SIGNING)?.isChecked == true

            findPreference<EditTextPreference>(ProofMode.PREF_OPTION_PROOFSIGN_SERVER)
                ?.isEnabled = isRemote

            val tsaPref = findPreference<EditTextPreference>(ProofMode.PREF_OPTION_TSA_SERVER)
            tsaPref?.isEnabled = !isRemote

        }
    }
}
