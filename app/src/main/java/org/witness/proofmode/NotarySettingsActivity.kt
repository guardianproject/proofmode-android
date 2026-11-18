package org.witness.proofmode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.witness.proofmode.databinding.ActivityNotarySettingsBinding

/**
 * Configures media notarization: a master enable plus per-provider toggles (OpenTimestamps,
 * Nostr and OctetProof), and a link to the Nostr identity screen. Reached by tapping "Notary" in
 * [SettingsActivity]. Mirrors [SigningSettingsActivity]'s toolbar + PreferenceFragment pattern.
 */
class NotarySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotarySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityNotarySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setTitleTextColor(getColor(R.color.colorPrimaryDark))
        binding.toolbar.setNavigationIconTint(getColor(R.color.colorPrimaryDark))
        supportActionBar?.title = getString(R.string.notary_settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_settings, NotaryPreferencesFragment())
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

    class NotaryPreferencesFragment : PreferenceFragmentCompat() {

        // Turning Octet on also turns on ProofMode location sharing, since Octet checks the
        // location recorded in each proof. Location + activity recognition (used by the Octet SDK)
        // are requested together; the Octet switch stays on even if the user denies them.
        private var pendingEnableLocation = false
        private var requestAttempted = false

        private val octetPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grantMap -> handleOctetPermissionResult(grantMap) }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.notary_preferences, rootKey)

            findPreference<Preference>("nostrIdentity")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), NostrIdentityActivity::class.java))
                true
            }

            // Octet only registers when configured in secrets.properties; hide the toggle otherwise.
            if (!org.witness.proofmode.org.witness.proofmode.octet.OctetSupport.isConfigured) {
                findPreference<Preference>(ProofMode.PREF_OPTION_NOTARY_OCTET)?.isVisible = false
            }

            findPreference<Preference>(ProofMode.PREF_OPTION_NOTARY_OCTET)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true) onOctetEnabled()
                    true
                }
        }

        private fun onOctetEnabled() {
            val ctx = requireContext()
            val missing = mutableListOf<String>()

            // Turns location sharing on now if permission is already granted.
            val plan = LocationSharingPermissionSync.beginEnable(ctx)
            if (plan.needsPermissionRequest) {
                pendingEnableLocation = true
                missing += LocationSharingPermissionSync.LOCATION_PERMISSIONS
            }

            // Runtime permission only from Android 10; granted at install time before that.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing += Manifest.permission.ACTIVITY_RECOGNITION
            }

            if (missing.isNotEmpty()) {
                requestAttempted = true
                octetPermissionLauncher.launch(missing.toTypedArray())
            }
        }

        private fun handleOctetPermissionResult(grantMap: Map<String, Boolean>) {
            if (!pendingEnableLocation) return // only activity recognition was asked for
            val result = LocationSharingPermissionSync.onPermissionLauncherResult(
                activity = requireActivity(),
                context = requireContext(),
                pendingEnable = true,
                requestAttempted = requestAttempted,
                grantMap = grantMap,
            )
            pendingEnableLocation = result.pendingEnable
            requestAttempted = false
            // Location was denied permanently: send the user to app info, as the location screen does.
            if (result.openAppInfo) {
                startActivity(
                    LocationSharingPermissionSync.applicationDetailsIntent(requireContext().packageName),
                )
            }
        }
    }
}
