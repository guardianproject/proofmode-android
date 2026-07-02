package org.witness.proofmode

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import org.witness.proofmode.databinding.ActivityDeveloperPreviewBinding
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin

class DeveloperPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDeveloperPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeveloperPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setTitleTextColor(getColor(R.color.colorPrimaryDark))
        binding.toolbar.setNavigationIconTint(getColor(R.color.colorPrimaryDark))

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.developer_preview_container, DeveloperPreviewFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class DeveloperPreviewFragment : PreferenceFragmentCompat() {
        override fun onResume() {
            super.onResume()
            refreshLpControlState()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = FeatureFlags.PREFS_NAME
            setPreferencesFromResource(R.xml.developer_preview_preferences, rootKey)

            val lpSwitch = findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)
            lpSwitch?.isChecked = FeatureFlags.lpEnabled
            lpSwitch?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                FeatureFlags.lpEnabled = enabled
                if (enabled) {
                    ExperimentalFeatureActivator.activateLocationProtocol(
                        requireActivity().application as ProofModeApp,
                    )
                }
                refreshLpControlState()
                true
            }

            val modeList = findPreference<ListPreference>(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE)
            modeList?.value = AutoCaptureLpMode.toPreference(FeatureFlags.autoCaptureLpMode)
            modeList?.setOnPreferenceChangeListener { _, newValue ->
                if (modeList.isEnabled) {
                    FeatureFlags.autoCaptureLpMode =
                        AutoCaptureLpMode.fromPreference(newValue as String)
                }
                true
            }

            val cidSwitch = findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)
            cidSwitch?.isChecked = FeatureFlags.localIpfsCidEnabled
            cidSwitch?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                FeatureFlags.localIpfsCidEnabled = enabled
                if (enabled) {
                    ExperimentalFeatureActivator.activateLocalIpfsCid(requireContext().applicationContext)
                }
                true
            }

            refreshLpControlState()
        }

        private fun refreshLpControlState() {
            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val locationEnabled = defaultPrefs.getBoolean(
                ProofMode.PREF_OPTION_LOCATION,
                ProofMode.PREF_OPTION_LOCATION_DEFAULT,
            )
            val walletConnected = if (LocationProtocolPlugin.isWalletStackRegistered()) {
                LocationProtocolPlugin.walletDiagnostics().connected
            } else {
                false
            }

            val lpSwitch = findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)
            val modeList = findPreference<ListPreference>(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE)

            lpSwitch?.isEnabled = locationEnabled
            modeList?.isEnabled = locationEnabled && walletConnected

            lpSwitch?.summary = if (locationEnabled) {
                getString(R.string.developer_preview_lp_summary)
            } else {
                getString(R.string.developer_preview_lp_auto_capture_summary_location_required)
            }

            modeList?.summary = when {
                !locationEnabled ->
                    getString(R.string.developer_preview_lp_auto_capture_summary_location_required)
                !walletConnected ->
                    getString(R.string.developer_preview_lp_auto_capture_summary_wallet_required)
                else -> null
            }
        }
    }
}
