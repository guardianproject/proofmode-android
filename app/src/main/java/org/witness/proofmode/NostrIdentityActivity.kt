package org.witness.proofmode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.databinding.ActivityNostrIdentityBinding
import org.witness.proofmode.notaries.NostrIdentityManager

/**
 * Manages the Nostr notary signing identity (the secp256k1 key used to sign notarizations):
 * view the current npub, auto-generate a new key, import an existing nsec, back up the key, or
 * (coming soon) delegate signing to the Amber remote signer over NIP-55.
 *
 * All key material is owned by [NostrIdentityManager] (android-nostr), the same source the
 * notarization provider signs with.
 */
class NostrIdentityActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNostrIdentityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNostrIdentityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setTitleTextColor(getColor(R.color.colorPrimaryDark))
        binding.toolbar.setNavigationIconTint(getColor(R.color.colorPrimaryDark))
        supportActionBar?.title = getString(R.string.nostr_identity_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_settings, NostrIdentityFragment())
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

    class NostrIdentityFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.nostr_identity_preferences, rootKey)

            findPreference<Preference>("nostrNpub")?.setOnPreferenceClickListener {
                copyCurrentNpub()
                true
            }

            findPreference<EditTextPreference>("nostrImportNsec")?.setOnPreferenceChangeListener { _, newValue ->
                val input = (newValue as? String)?.trim().orEmpty()
                if (input.isNotEmpty()) importNsec(input)
                false // never persist the raw nsec under this preference key
            }

            findPreference<Preference>("nostrGenerate")?.setOnPreferenceClickListener {
                confirmGenerate()
                true
            }

            findPreference<Preference>("nostrBackup")?.setOnPreferenceClickListener {
                backupNsec()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            refreshNpub()
        }

        private fun refreshNpub() {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                val npub = withContext(Dispatchers.IO) { NostrIdentityManager.getNpub(appCtx) }
                setNpubSummary(npub)
            }
        }

        private fun setNpubSummary(npub: String?) {
            findPreference<Preference>("nostrNpub")?.summary =
                npub ?: getString(R.string.nostr_identity_none)
        }

        private fun copyCurrentNpub() {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                val npub = withContext(Dispatchers.IO) { NostrIdentityManager.getNpub(appCtx) }
                if (npub == null) {
                    toast(getString(R.string.nostr_identity_none))
                } else {
                    copyToClipboard("npub", npub)
                    toast(getString(R.string.nostr_identity_copied))
                }
            }
        }

        private fun importNsec(input: String) {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { NostrIdentityManager.import(appCtx, input) }
                }
                result.onSuccess { npub ->
                    setNpubSummary(npub)
                    toast(getString(R.string.nostr_identity_import_ok))
                }.onFailure {
                    toast(getString(R.string.nostr_identity_import_bad))
                }
            }
        }

        private fun confirmGenerate() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.nostr_identity_generate_title)
                .setMessage(R.string.nostr_identity_generate_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val appCtx = requireContext().applicationContext
                    lifecycleScope.launch {
                        val npub = withContext(Dispatchers.IO) { NostrIdentityManager.generate(appCtx) }
                        setNpubSummary(npub)
                        toast(getString(R.string.nostr_identity_generate_ok))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun backupNsec() {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                val nsec = withContext(Dispatchers.IO) { NostrIdentityManager.getNsec(appCtx) }
                if (nsec == null) {
                    toast(getString(R.string.nostr_identity_none))
                    return@launch
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.nostr_identity_backup_title)
                    .setMessage(R.string.nostr_identity_backup_warning)
                    .setPositiveButton(R.string.nostr_identity_backup_copy) { _, _ ->
                        copyToClipboard("nsec", nsec)
                        toast(getString(R.string.nostr_identity_copied))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        private fun copyToClipboard(label: String, value: String) {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, value))
        }

        private fun toast(message: String) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
