package org.witness.proofmode

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import org.witness.proofmode.databinding.ActivityLocationSettingsBinding
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.wallet.WalletSettingsActivity

/**
 * Location sharing, Location Protocol, CID, and wallet navigation. Reached from the Location
 * cell in [SettingsActivity]. Mirrors [NotarySettingsActivity]'s toolbar + PreferenceFragment
 * pattern; fragment rows bind to [FeatureFlags.PREFS_NAME] except the virtual master switch.
 */
class LocationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLocationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setTitleTextColor(getColor(R.color.colorPrimaryDark))
        binding.toolbar.setNavigationIconTint(getColor(R.color.colorPrimaryDark))
        supportActionBar?.title = getString(R.string.location_settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.location_settings_container, LocationPreferencesFragment())
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

    class LocationPreferencesFragment : PreferenceFragmentCompat() {

        private var pendingEnableLocation = false
        private var requestAttemptedThisCycle = false

        private val locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grantMap ->
            handleLocationPermissionResult(grantMap)
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putBoolean(KEY_PENDING_ENABLE_LOCATION, pendingEnableLocation)
            outState.putBoolean(KEY_REQUEST_ATTEMPTED, requestAttemptedThisCycle)
        }

        override fun onResume() {
            super.onResume()
            val snap = LocationSharingPermissionSync.reconcileOnResume(
                requireContext(),
                pendingEnableLocation,
            )
            pendingEnableLocation = snap.pendingEnable
            reconcileMasterFromPrefs()
            if (FeatureFlags.lpEnabled &&
                (!LocationProtocolPlugin.isWalletStackRegistered() ||
                    !LocationProtocolPlugin.isFlutterEngineReady())
            ) {
                ExperimentalFeatureActivator.activateLocationProtocol(
                    requireActivity().application as ProofModeApp,
                )
            }
            refreshEnabledState()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            if (savedInstanceState != null) {
                pendingEnableLocation = savedInstanceState.getBoolean(
                    KEY_PENDING_ENABLE_LOCATION,
                    pendingEnableLocation,
                )
                requestAttemptedThisCycle = savedInstanceState.getBoolean(
                    KEY_REQUEST_ATTEMPTED,
                    requestAttemptedThisCycle,
                )
            }
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    ExperimentalFeatureActivator.activationState.collect { refreshEnabledState() }
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = FeatureFlags.PREFS_NAME
            setPreferencesFromResource(R.xml.location_preferences, rootKey)
            pendingEnableLocation =
                savedInstanceState?.getBoolean(KEY_PENDING_ENABLE_LOCATION, false) ?: false
            requestAttemptedThisCycle =
                savedInstanceState?.getBoolean(KEY_REQUEST_ATTEMPTED, false) ?: false

            reconcileMasterFromPrefs()
            wireListeners()
            refreshEnabledState()
        }

        private fun reconcileMasterFromPrefs() {
            findPreference<SwitchPreferenceCompat>(KEY_MASTER)?.isChecked =
                defaultPrefs().getBoolean(
                    ProofMode.PREF_OPTION_LOCATION,
                    ProofMode.PREF_OPTION_LOCATION_DEFAULT,
                )
        }

        private fun wireListeners() {
            findPreference<SwitchPreferenceCompat>(KEY_MASTER)?.setOnPreferenceChangeListener { _, newValue ->
                if (pendingEnableLocation) return@setOnPreferenceChangeListener false
                val enable = newValue as Boolean
                if (enable) {
                    val plan = LocationSharingPermissionSync.beginEnable(requireContext())
                    if (plan.needsPermissionRequest) {
                        pendingEnableLocation = true
                        requestAttemptedThisCycle = true
                        locationPermissionLauncher.launch(
                            LocationSharingPermissionSync.LOCATION_PERMISSIONS,
                        )
                        return@setOnPreferenceChangeListener false
                    }
                    reconcileMasterFromPrefs()
                    refreshEnabledState()
                    true
                } else {
                    val snap = LocationSharingPermissionSync.beginDisable(requireContext())
                    pendingEnableLocation = snap.pendingEnable
                    reconcileMasterFromPrefs()
                    refreshEnabledState()
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)?.apply {
                isChecked = FeatureFlags.lpEnabled
                setOnPreferenceChangeListener { preference, newValue ->
                    val enabled = newValue as Boolean
                    val priorChecked = (preference as SwitchPreferenceCompat).isChecked
                    val enabling = enabled && !priorChecked
                    FeatureFlags.lpEnabled = enabled
                    if (enabled) {
                        if (enabling) {
                            maybeAutoEnableCidOnFirstLpEnable()
                        }
                        ExperimentalFeatureActivator.activateLocationProtocol(
                            requireActivity().application as ProofModeApp,
                        )
                    } else {
                        Toast.makeText(
                            requireContext(),
                            R.string.location_protocol_disable_restart_notice,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    refreshEnabledState()
                    true
                }
            }

            findPreference<ListPreference>(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE)?.apply {
                value = AutoCaptureLpMode.toPreference(FeatureFlags.autoCaptureLpMode)
                setOnPreferenceChangeListener { preference, newValue ->
                    if (preference.isEnabled) {
                        FeatureFlags.autoCaptureLpMode =
                            AutoCaptureLpMode.fromPreference(newValue as String)
                    }
                    refreshEnabledState()
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)?.apply {
                isChecked = FeatureFlags.localIpfsCidEnabled
                setOnPreferenceChangeListener { preference, newValue ->
                    if (!preference.isEnabled) return@setOnPreferenceChangeListener false
                    val enabled = newValue as Boolean
                    FeatureFlags.localIpfsCidEnabled = enabled
                    FeatureFlags.localIpfsCidUserSet = true // manual only — never from CR3
                    if (enabled) {
                        ExperimentalFeatureActivator.activateLocalIpfsCid(requireContext().applicationContext)
                    }
                    refreshEnabledState()
                    true
                }
            }

            findPreference<Preference>(KEY_WALLET)?.setOnPreferenceClickListener { preference ->
                if (preference.isEnabled) {
                    startActivity(Intent(requireContext(), WalletSettingsActivity::class.java))
                }
                true
            }
        }

        /** Package-visible for Robolectric T8 coverage of the launcher callback body. */
        internal fun handleLocationPermissionResult(grantMap: Map<String, Boolean>) {
            val result = LocationSharingPermissionSync.onPermissionLauncherResult(
                activity = requireActivity(),
                context = requireContext(),
                pendingEnable = pendingEnableLocation,
                requestAttempted = requestAttemptedThisCycle,
                grantMap = grantMap,
            )
            pendingEnableLocation = result.pendingEnable
            requestAttemptedThisCycle = false
            if (result.openAppInfo) {
                startActivity(
                    LocationSharingPermissionSync.applicationDetailsIntent(
                        requireContext().packageName,
                    ),
                )
            }
            reconcileMasterFromPrefs()
            refreshEnabledState()
        }

        /**
         * D13 / F25 — listener-only. Call after lpEnabled write, before activateLocationProtocol.
         */
        private fun maybeAutoEnableCidOnFirstLpEnable() {
            if (FeatureFlags.lpCidAutoCouplingApplied || FeatureFlags.localIpfsCidUserSet) return
            if (FeatureFlags.localIpfsCidEnabled) {
                FeatureFlags.markLpCidAutoCouplingAppliedOnly()
                return
            }
            FeatureFlags.applyLpCidAutoEnable()
            ExperimentalFeatureActivator.activateLocalIpfsCid(requireContext().applicationContext)
            findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)?.isChecked =
                FeatureFlags.localIpfsCidEnabled
        }

        private fun refreshEnabledState() {
            val locationSharingEnabled = FeatureFlags.locationSharingEnabled
            val walletStackRegistered = LocationProtocolPlugin.isWalletStackRegistered()
            val walletDiagnostics = LocationProtocolPlugin.walletDiagnostics()
            val walletConnected = if (walletStackRegistered) walletDiagnostics.connected else false

            val lpSwitch = findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
            val modeList = findPreference<ListPreference>(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE)!!
            val cidSwitch = findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)!!
            val walletRow = findPreference<Preference>(KEY_WALLET)!!

            val inputs = LocationSettingsCascade.Inputs(
                locationSharingEnabled = locationSharingEnabled,
                lpEnabled = FeatureFlags.lpEnabled,
                walletConnected = walletConnected,
                walletDiagnosticsConnected = walletDiagnostics.connected,
                walletStackRegistered = walletStackRegistered,
                activationState = ExperimentalFeatureActivator.activationState.value,
                lpSwitchChecked = lpSwitch.isChecked,
                cidSwitchChecked = cidSwitch.isChecked,
                walletAbbreviatedAddress = walletDiagnostics.abbreviatedAddress(),
            )

            applyPresentation(lpSwitch, LocationSettingsCascade.lpRowPresentation(inputs))
            applyPresentation(modeList, LocationSettingsCascade.autoCapturePresentation(inputs))
            applyPresentation(cidSwitch, LocationSettingsCascade.cidPresentation(inputs))
            applyPresentation(walletRow, LocationSettingsCascade.walletPresentation(inputs))
        }

        private fun applyPresentation(preference: Preference, presentation: LocationSettingsCascade.RowPresentation) {
            preference.isEnabled = presentation.enabled
            preference.summary = if (presentation.summaryFormatArg != null) {
                getString(presentation.summaryResId, presentation.summaryFormatArg)
            } else {
                getString(presentation.summaryResId)
            }
        }

        private fun defaultPrefs() =
            PreferenceManager.getDefaultSharedPreferences(requireContext())

        companion object {
            private const val KEY_MASTER = "locationSharingMaster"
            private const val KEY_WALLET = "walletSettings"
            internal const val KEY_PENDING_ENABLE_LOCATION = "pendingEnableLocation"
            internal const val KEY_REQUEST_ATTEMPTED = "requestAttemptedThisCycle"
        }
    }
}
