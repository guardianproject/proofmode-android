package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.witness.proofmode.LocationSettingsActivityTestSupport.fragment
import org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry
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

    @After
    fun tearDown() {
        LocationSettingsActivityTestSupport.tearDown()
    }

    private fun launchFragment(): LocationSettingsActivity.LocationPreferencesFragment {
        val activity = LocationSettingsActivityTestSupport.launchLocationSettingsActivity()
        TestWalletStackHelper.setupFakeKeyStore()
        LocationProtocolPlugin.registerWalletStack(activity.application as ProofModeApp)
        LocationProtocolPlugin.setFlutterEngineReadyForTests(true)
        ExperimentalFeatureActivator.resetActivationStateForTests(LpActivationState.Ready)
        val fragment = fragment(activity)
        fragment.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        return fragment
    }

    @Test
    fun firstLpOn_whenNeverUserSet_autoEnablesCid_activates_andSetsCoupling() {
        val fragment = launchFragment()
        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!

        lp.callChangeListener(true)
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

        lp.callChangeListener(true)
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
        cid.callChangeListener(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(FeatureFlags.localIpfsCidUserSet)

        cid.isEnabled = true
        cid.callChangeListener(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.localIpfsCidUserSet)

        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
        lp.callChangeListener(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertFalse(FeatureFlags.lpCidAutoCouplingApplied)
        assertTrue(FeatureFlags.localIpfsCidUserSet)
    }

    @Test
    fun afterCouplingApplied_lpOffOn_doesNotReForceCid() {
        val fragment = launchFragment()
        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!
        lp.callChangeListener(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)

        // Clear CID without touching user_set (isolates coupling latch).
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED, false)
            .commit()
        FeatureFlags.resetForTests(context)
        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)

        lp.callChangeListener(false)
        shadowOf(Looper.getMainLooper()).idle()
        lp.callChangeListener(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)
    }

    @Test
    fun onCidToggle_offOnOffOn_hookCountsStayAtOne_t3() {
        FeatureFlags.lpEnabled = true
        val fragment = launchFragment()
        val cid = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)!!
        cid.isEnabled = true

        cid.callChangeListener(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
        assertEquals(1, ProofArtifactSavedHookRegistry.registeredCountForTests())

        cid.callChangeListener(false)
        shadowOf(Looper.getMainLooper()).idle()

        cid.callChangeListener(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
        assertEquals(1, ProofArtifactSavedHookRegistry.registeredCountForTests())
    }
}
