package org.witness.proofmode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.c2pa.C2PAManager
import org.witness.proofmode.c2pa.PreferencesManager
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

            findPreference<Preference>(KEY_GENERATE_CAWG)?.setOnPreferenceClickListener {
                confirmGenerateCawgIdentity()
                true
            }

            findPreference<Preference>(KEY_EDIT_CAWG)?.setOnPreferenceClickListener {
                showEditCawgDialog()
                true
            }

            applyModeState()
        }

        /**
         * Show a dialog with two editable boxes pre-filled with the stored CAWG private key
         * and certificate PEMs. SAVE validates the PEMs, asks for confirmation, and then
         * overwrites the stored identity; CANCEL discards the edits.
         */
        private fun showEditCawgDialog() {
            val context = context ?: return
            val appContext = context.applicationContext
            val c2paManager = C2PAManager(appContext, PreferencesManager(appContext))

            val view = layoutInflater.inflate(R.layout.dialog_edit_cawg, null)
            val keyEdit = view.findViewById<EditText>(R.id.editCawgPrivateKey)
            val certEdit = view.findViewById<EditText>(R.id.editCawgCertChain)
            keyEdit.setText(c2paManager.getCawgPrivateKey().orEmpty())
            certEdit.setText(c2paManager.getCawgCertChain().orEmpty())

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.settings_signing_cawg_edit)
                .setView(view)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            // Override the positive button click so a failed validation keeps the dialog
            // open instead of dismissing it (the default behaviour).
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val keyPem = keyEdit.text.toString().trim()
                    val certPem = certEdit.text.toString().trim()
                    if (!keyPem.contains("PRIVATE KEY")) {
                        toast(getString(R.string.settings_signing_cawg_edit_key_invalid))
                        return@setOnClickListener
                    }
                    if (!certPem.contains("CERTIFICATE")) {
                        toast(getString(R.string.settings_signing_cawg_edit_cert_invalid))
                        return@setOnClickListener
                    }
                    confirmSaveCawg(c2paManager, keyPem, certPem) { dialog.dismiss() }
                }
            }
            dialog.show()
        }

        /** Second-stage confirm before overwriting the stored CAWG identity. */
        private fun confirmSaveCawg(
            c2paManager: C2PAManager,
            keyPem: String,
            certPem: String,
            onSaved: () -> Unit
        ) {
            val context = context ?: return
            AlertDialog.Builder(context)
                .setTitle(R.string.settings_signing_cawg_edit_confirm_title)
                .setMessage(R.string.settings_signing_cawg_edit_confirm_message)
                .setPositiveButton(R.string.action_save) { _, _ ->
                    c2paManager.importCawgIdentity(keyPem, certPem)
                    MediaWatcher.getInstance(activity)?.resetC2PA()
                    toast(getString(R.string.settings_signing_cawg_edit_ok))
                    onSaved()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun toast(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        /** Confirm before generating, since it wipes out the current CAWG key and certificate. */
        private fun confirmGenerateCawgIdentity() {
            val context = context ?: return
            AlertDialog.Builder(context)
                .setTitle(R.string.settings_signing_cawg_generate_confirm_title)
                .setMessage(R.string.settings_signing_cawg_generate_confirm_message)
                .setPositiveButton(R.string.action_generate_short) { _, _ ->
                    generateCawgIdentity()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /**
         * (Re)generate the CAWG identity key using the configured creator name, then show
         * the resulting certificate signing request (CSR) so the user can submit it to an
         * official CA to obtain a properly signed certificate.
         */
        private fun generateCawgIdentity() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val creatorName = prefs.getString(ProofMode.PREF_CAWG_CREATOR, null)
                ?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
            val appContext = requireContext().applicationContext

            lifecycleScope.launch {
                val csr = withContext(Dispatchers.IO) {
                    val c2paManager = C2PAManager(appContext, PreferencesManager(appContext))
                    c2paManager.createCawgIdentity(creatorName, true)
                    c2paManager.getCawgCSR()
                }
                showCawgCsrDialog(csr)
            }
        }

        private fun showCawgCsrDialog(csr: String?) {
            val context = context ?: return
            val builder = AlertDialog.Builder(context)
                .setTitle("CAWG Certificate Request")
                .setMessage(csr ?: "Unable to generate certificate request.")
                .setPositiveButton(android.R.string.ok, null)

            if (csr != null) {
                builder.setNeutralButton(android.R.string.copy) { _, _ ->
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("CAWG CSR", csr))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }

            builder.show()
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
            private const val KEY_GENERATE_CAWG = "generateCawg"
            private const val KEY_EDIT_CAWG = "editCawg"
            private const val MODE_REMOTE = "remote"
            private const val MODE_LOCAL = "local"
            private const val MODE_DISABLED = "disabled"
        }
    }
}
