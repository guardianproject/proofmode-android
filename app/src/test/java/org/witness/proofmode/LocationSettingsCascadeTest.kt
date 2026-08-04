package org.witness.proofmode

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class LocationSettingsCascadeTest {

    private lateinit var context: Context
    private lateinit var featureFlags: SharedPreferences
    private lateinit var defaultPrefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        featureFlags = context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        featureFlags.edit().clear().commit()
        defaultPrefs.edit().clear().commit()
        FeatureFlags.resetForTests(context)
        ExperimentalFeatureActivator.resetActivationStateForTests(LpActivationState.Ready)
    }

    private fun baseInputs(
        locationSharingEnabled: Boolean = true,
        lpEnabled: Boolean = true,
        walletConnected: Boolean = false,
        walletDiagnosticsConnected: Boolean = false,
        walletStackRegistered: Boolean = false,
        activationState: LpActivationState = LpActivationState.Ready,
        lpSwitchChecked: Boolean = lpEnabled,
        cidSwitchChecked: Boolean = false,
        walletAbbreviatedAddress: String? = null,
    ) = LocationSettingsCascade.Inputs(
        locationSharingEnabled = locationSharingEnabled,
        lpEnabled = lpEnabled,
        walletConnected = walletConnected,
        walletDiagnosticsConnected = walletDiagnosticsConnected,
        walletStackRegistered = walletStackRegistered,
        activationState = activationState,
        lpSwitchChecked = lpSwitchChecked,
        cidSwitchChecked = cidSwitchChecked,
        walletAbbreviatedAddress = walletAbbreviatedAddress,
    )

    @Test
    fun masterOff_disablesLpAutoCaptureCidAndWalletWithLocationRequiredSummaries() {
        val inputs = baseInputs(locationSharingEnabled = false, lpEnabled = true)
        assertFalse(LocationSettingsCascade.lpRowEnabled(inputs))
        assertFalse(LocationSettingsCascade.autoCaptureEnabled(inputs))
        assertFalse(LocationSettingsCascade.cidEnabled(inputs))
        assertFalse(LocationSettingsCascade.walletRowEnabled(inputs))
        assertEquals(
            R.string.location_protocol_enable_summary_location_required,
            LocationSettingsCascade.lpRowPresentation(inputs).summaryResId,
        )
        assertEquals(
            R.string.location_protocol_auto_capture_summary_location_required,
            LocationSettingsCascade.autoCapturePresentation(inputs).summaryResId,
        )
        assertEquals(
            R.string.compute_cids_enable_summary_location_required,
            LocationSettingsCascade.cidPresentation(inputs).summaryResId,
        )
        assertEquals(
            R.string.location_wallet_summary_location_required,
            LocationSettingsCascade.walletPresentation(inputs).summaryResId,
        )
    }

    @Test
    fun masterOff_walletStaysEnabledWhenConnected_f13() {
        val inputs = baseInputs(
            locationSharingEnabled = false,
            lpEnabled = false,
            walletDiagnosticsConnected = true,
            walletAbbreviatedAddress = "0x1234…abcd",
        )
        assertTrue(LocationSettingsCascade.walletRowEnabled(inputs))
        val wallet = LocationSettingsCascade.walletPresentation(inputs)
        assertEquals(R.string.location_wallet_summary_connected, wallet.summaryResId)
        assertEquals("0x1234…abcd", wallet.summaryFormatArg)
    }

    @Test
    fun masterOnLpOff_cidUsesLpRequiredSummary_walletLpRequiredUnlessConnected() {
        val disconnected = baseInputs(locationSharingEnabled = true, lpEnabled = false)
        assertEquals(
            R.string.compute_cids_enable_summary_lp_required,
            LocationSettingsCascade.cidPresentation(disconnected).summaryResId,
        )
        assertEquals(
            R.string.location_wallet_summary_lp_required,
            LocationSettingsCascade.walletPresentation(disconnected).summaryResId,
        )

        val connected = baseInputs(
            locationSharingEnabled = true,
            lpEnabled = false,
            walletDiagnosticsConnected = true,
            walletAbbreviatedAddress = "0xabcd…ef01",
        )
        assertTrue(LocationSettingsCascade.walletRowEnabled(connected))
        assertEquals(
            R.string.location_wallet_summary_connected,
            LocationSettingsCascade.walletPresentation(connected).summaryResId,
        )
    }

    @Test
    fun masterOnLpOnDisconnected_onlyAutoCaptureWalletRequired() {
        val inputs = baseInputs(
            locationSharingEnabled = true,
            lpEnabled = true,
            walletConnected = false,
            walletStackRegistered = true,
        )
        assertTrue(LocationSettingsCascade.lpRowEnabled(inputs))
        assertFalse(LocationSettingsCascade.autoCaptureEnabled(inputs))
        assertTrue(LocationSettingsCascade.cidEnabled(inputs))
        assertTrue(LocationSettingsCascade.walletRowEnabled(inputs))
        assertEquals(
            R.string.location_protocol_auto_capture_summary_wallet_required,
            LocationSettingsCascade.autoCapturePresentation(inputs).summaryResId,
        )
        assertEquals(
            R.string.location_wallet_row_summary,
            LocationSettingsCascade.walletPresentation(inputs).summaryResId,
        )
    }

    @Test
    fun activating_disablesLpRowAndDependents() {
        val inputs = baseInputs(
            locationSharingEnabled = true,
            lpEnabled = true,
            walletStackRegistered = true,
            activationState = LpActivationState.Activating,
        )
        assertFalse(LocationSettingsCascade.lpRowEnabled(inputs))
        assertFalse(LocationSettingsCascade.autoCaptureEnabled(inputs))
        assertFalse(LocationSettingsCascade.cidEnabled(inputs))
        assertEquals(
            R.string.location_protocol_enable_activating,
            LocationSettingsCascade.lpRowPresentation(inputs).summaryResId,
        )
    }

    @Test
    fun failedActivation_disablesDependents_i13() {
        val inputs = baseInputs(
            locationSharingEnabled = true,
            lpEnabled = true,
            walletConnected = true,
            walletStackRegistered = true,
            activationState = LpActivationState.Failed,
        )
        assertFalse(LocationSettingsCascade.autoCaptureEnabled(inputs))
        assertFalse(LocationSettingsCascade.cidEnabled(inputs))
        assertEquals(
            R.string.location_protocol_enable_failed,
            LocationSettingsCascade.lpRowPresentation(inputs).summaryResId,
        )
        assertEquals(
            R.string.location_protocol_auto_capture_summary,
            LocationSettingsCascade.autoCapturePresentation(inputs).summaryResId,
        )
        assertEquals(
            R.string.compute_cids_enable_summary_off,
            LocationSettingsCascade.cidPresentation(inputs).summaryResId,
        )
    }

    @Test
    fun presentations_neverUseNullSummaryFormatArg() {
        val inputs = baseInputs()
        listOf(
            LocationSettingsCascade.lpRowPresentation(inputs),
            LocationSettingsCascade.autoCapturePresentation(inputs),
            LocationSettingsCascade.cidPresentation(inputs),
            LocationSettingsCascade.walletPresentation(inputs),
        ).forEach { presentation ->
            assertNotNull(presentation.summaryResId)
        }
    }

    @Test
    fun onOpen_masterOff_activityRowsMatchCascade() {
        defaultPrefs.edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).apply()
        FeatureFlags.lpEnabled = true
        val activity = launchActivity()
        val fragment = activity.supportFragmentManager
            .findFragmentById(R.id.location_settings_container) as LocationSettingsActivity.LocationPreferencesFragment

        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
        val mode = fragment.findPreference<ListPreference>(FeatureFlags.KEY_LP_AUTO_CAPTURE_MODE)!!
        val cid = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)!!
        val wallet = fragment.findPreference<Preference>("walletSettings")!!

        assertFalse(lp.isEnabled)
        assertFalse(mode.isEnabled)
        assertFalse(cid.isEnabled)
        assertFalse(wallet.isEnabled)
        assertNotNull(lp.summary)
        assertNotNull(mode.summary)
        assertNotNull(cid.summary)
        assertNotNull(wallet.summary)
    }

    private fun launchActivity(): LocationSettingsActivity =
        LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
}
