package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.plugins.ipfscid.IpfsCidPlugin
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.util.TestWalletStackHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class LocationSettingsLpCidCouplingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
            .commit()
        FeatureFlags.resetForTests(context)
        FeatureFlags.lpEnabled = false
        FeatureFlags.localIpfsCidEnabled = false
        ExperimentalFeatureActivator.resetActivationStateForTests(LpActivationState.Ready)
        IpfsCidPlugin.clearRegistrationStateForTests()
        shadowOf(context.applicationContext as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private fun launchFragment(): LocationSettingsActivity.LocationPreferencesFragment {
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        TestWalletStackHelper.setupFakeKeyStore()
        LocationProtocolPlugin.registerWalletStack(activity.application as ProofModeApp)
        LocationProtocolPlugin.setFlutterEngineReadyForTests(true)
        ExperimentalFeatureActivator.resetActivationStateForTests(LpActivationState.Ready)
        val fragment = activity.supportFragmentManager
            .findFragmentById(R.id.location_settings_container)
            as LocationSettingsActivity.LocationPreferencesFragment
        fragment.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        return fragment
    }

    private fun callChangeListener(preference: Preference, newValue: Any) {
        val method = Preference::class.java.getDeclaredMethod("callChangeListener", Any::class.java)
        method.isAccessible = true
        method.invoke(preference, newValue)
    }

    @Test
    fun openLocationOnly_doesNotAutoEnableCid_orSetCoupling() {
        launchFragment()
        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertFalse(FeatureFlags.lpCidAutoCouplingApplied)
        assertFalse(FeatureFlags.localIpfsCidUserSet)
    }

    @Test
    fun firstLpOn_whenNeverUserSet_autoEnablesCid_activates_andSetsCoupling() {
        val fragment = launchFragment()
        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
        lp.isChecked = false

        callChangeListener(lp, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(FeatureFlags.lpEnabled)
        assertTrue(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)
        assertFalse(FeatureFlags.localIpfsCidUserSet)
        assertTrue(ProofWriteHookRegistry.registeredCountForTests() >= 1)
    }

    @Test
    fun firstLpOn_whenCidAlreadyTrue_setsCouplingOnly_noUserSet() {
        FeatureFlags.localIpfsCidEnabled = true
        val fragment = launchFragment()
        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
        lp.isChecked = false

        callChangeListener(lp, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)
        assertFalse(FeatureFlags.localIpfsCidUserSet)
    }

    @Test
    fun manualCidToggle_setsUserSet_andBlocksLaterAutoEnable() {
        val fragment = launchFragment()
        val cid = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)!!
        // LP is off (cascade would gray CID); force-enable for manual toggle coverage.
        cid.isEnabled = true
        callChangeListener(cid, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(FeatureFlags.localIpfsCidUserSet)

        cid.isEnabled = true
        callChangeListener(cid, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.localIpfsCidUserSet)

        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
        lp.isChecked = false
        callChangeListener(lp, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.localIpfsCidUserSet)
    }

    @Test
    fun afterCouplingApplied_lpOffOn_doesNotReForceCid() {
        val fragment = launchFragment()
        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
        lp.isChecked = false
        callChangeListener(lp, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)

        // Clear CID without touching user_set (isolates coupling latch).
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED, false)
            .commit()
        FeatureFlags.resetForTests(context)
        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)

        callChangeListener(lp, false)
        shadowOf(Looper.getMainLooper()).idle()
        lp.isChecked = false
        callChangeListener(lp, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)
    }

    @Test
    fun userSetTrue_evenIfCidFalse_blocksAutoEnableOnFirstLpOn() {
        FeatureFlags.localIpfsCidUserSet = true
        FeatureFlags.localIpfsCidEnabled = false
        val fragment = launchFragment()
        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
        lp.isChecked = false

        callChangeListener(lp, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertFalse(FeatureFlags.lpCidAutoCouplingApplied)
        assertTrue(FeatureFlags.localIpfsCidUserSet)
    }
}
