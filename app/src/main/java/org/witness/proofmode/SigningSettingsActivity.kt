package org.witness.proofmode

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import org.witness.proofmode.databinding.ActivitySettingsBinding
import org.witness.proofmode.databinding.ActivitySigningSettingsBinding
import org.witness.proofmode.library.BuildConfig
import org.witness.proofmode.service.MediaWatcher

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
            setPreferencesFromResource(R.xml.signing_preferences, rootKey)

            // The Signing Mode list is the UI face of two canonical booleans the rest of the
            // app reads: PREF_OPTION_CREDENTIALS (C2PA on/off) and PREF_OPTION_REMOTE_SIGNING
            // (remote vs local). Seed the list from them, then translate selections back.
            findPreference<ListPreference>(KEY_SIGNING_MODE)?.apply {
                value = currentModeValue()
                setOnPreferenceChangeListener { _, newValue ->
                    applyMode(newValue as String)
                    true
                }
            }

            // For the remote signing servers, show the user's configured value when set, and
            // otherwise fall back to the env-secret default the signer actually uses (see
            // ProofModeApp/C2PAManager). These mirror BuildConfig.SIGNING_SERVER/TSA_SERVER.
            configureServerDefault(ProofMode.PREF_OPTION_PROOFSIGN_SERVER, BuildConfig.SIGNING_SERVER)
            configureServerDefault(ProofMode.PREF_OPTION_TSA_SERVER, BuildConfig.TSA_SERVER)

            applyModeState()
        }

        /**
         * Wire an EditTextPreference so its summary shows the configured value or, when blank,
         * [default]; and so opening its dialog pre-fills [default] instead of an empty field.
         * We never persist [default] ourselves — the signer already falls back to it when the
         * stored value is empty, so seeding only happens if the user explicitly saves.
         */
        private fun configureServerDefault(key: String, default: String) {
            findPreference<EditTextPreference>(key)?.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    pref.text?.takeIf { it.isNotBlank() } ?: default
                }
                setOnBindEditTextListener { editText ->
                    if (editText.text.isNullOrBlank()) {
                        editText.setText(default)
                        editText.setSelection(editText.text.length)
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            // Re-sync in case the mode was changed elsewhere (e.g. the Settings screen).
            findPreference<ListPreference>(KEY_SIGNING_MODE)?.value = currentModeValue()
            preferenceManager.sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            applyModeState()
        }

        /** Derive the list value from the canonical credential booleans. */
        private fun currentModeValue(): String {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val credentialsEnabled = prefs.getBoolean(
                ProofMode.PREF_OPTION_CREDENTIALS,
                ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT
            )
            if (!credentialsEnabled) return MODE_DISABLED
            val isRemote = prefs.getBoolean(
                ProofMode.PREF_OPTION_REMOTE_SIGNING,
                ProofMode.PREF_OPTION_REMOTE_SIGNING_DEFAULT
            )
            return if (isRemote) MODE_REMOTE else MODE_LOCAL
        }

        /** Translate a list selection into the canonical credential booleans. */
        private fun applyMode(mode: String) {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().apply {
                when (mode) {
                    MODE_REMOTE -> {
                        putBoolean(ProofMode.PREF_OPTION_CREDENTIALS, true)
                        putBoolean(ProofMode.PREF_OPTION_REMOTE_SIGNING, true)
                    }
                    MODE_LOCAL -> {
                        putBoolean(ProofMode.PREF_OPTION_CREDENTIALS, true)
                        putBoolean(ProofMode.PREF_OPTION_REMOTE_SIGNING, false)
                    }
                    MODE_DISABLED -> putBoolean(ProofMode.PREF_OPTION_CREDENTIALS, false)
                }
            }.commit()
            applyModeState()
        }

        private fun applyModeState() {
            findPreference<EditTextPreference>(ProofMode.PREF_OPTION_PROOFSIGN_SERVER)
                ?.isEnabled = currentModeValue() == MODE_REMOTE

            MediaWatcher.getInstance(activity)?.resetC2PA()
        }

        companion object {
            private const val KEY_SIGNING_MODE = "signingMode"
            private const val MODE_REMOTE = "remote"
            private const val MODE_LOCAL = "local"
            private const val MODE_DISABLED = "disabled"
        }
    }
}
