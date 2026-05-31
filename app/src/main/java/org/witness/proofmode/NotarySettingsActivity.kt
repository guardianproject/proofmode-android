package org.witness.proofmode

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.witness.proofmode.databinding.ActivityNotarySettingsBinding

/**
 * Configures media notarization: a master enable plus per-provider toggles (OpenTimestamps and
 * Nostr), and a link to the Nostr identity screen. Reached by tapping "Notary" in
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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.notary_preferences, rootKey)

            findPreference<Preference>("nostrIdentity")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), NostrIdentityActivity::class.java))
                true
            }
        }
    }
}
