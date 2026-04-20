package org.witness.proofmode

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat

class SigningSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.settings_signing_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SigningPreferencesFragment())
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
            val isRemote = prefs.getBoolean(
                ProofMode.PREF_OPTION_REMOTE_SIGNING,
                ProofMode.PREF_OPTION_REMOTE_SIGNING_DEFAULT
            )

            findPreference<EditTextPreference>(ProofMode.PREF_OPTION_PROOFSIGN_SERVER)
                ?.isEnabled = isRemote

            val tsaPref = findPreference<EditTextPreference>(ProofMode.PREF_OPTION_TSA_SERVER)
            tsaPref?.isEnabled = !isRemote

            if (isRemote) {
                // Remote signing always uses the pinned TSA.
                tsaPref?.text = ProofMode.PREF_OPTION_TSA_SERVER_DEFAULT
            }
        }
    }
}
