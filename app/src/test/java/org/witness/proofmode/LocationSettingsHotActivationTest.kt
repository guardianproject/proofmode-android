package org.witness.proofmode

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.plugins.ipfscid.IpfsCidPlugin
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.util.TestWalletStackHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class LocationSettingsHotActivationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, true)
            .commit()
        FeatureFlags.resetForTests(context)
        FeatureFlags.lpEnabled = true
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

    @Test
    fun onLpToggleOn_persistsFlagBeforeActivatorRuns_t2() {
        FeatureFlags.lpEnabled = false
        val fragment = launchFragment()
        val lp = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LP_ENABLED)!!

        callChangeListener(lp, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(FeatureFlags.lpEnabled)
        assertEquals(true, ExperimentalFeatureActivator.lpEnabledAtActivationEntryForTests.get())
        assertTrue(
            context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(FeatureFlags.KEY_LP_ENABLED, false),
        )
    }

    @Test
    fun onCidToggleOff_writesOnly_doesNotInvokeActivator() {
        FeatureFlags.localIpfsCidEnabled = true
        IpfsCidPlugin.clearRegistrationStateForTests()
        val fragment = launchFragment()
        val cid = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)!!
        cid.isEnabled = true

        callChangeListener(cid, false)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertEquals(0, ProofWriteHookRegistry.registeredCountForTests())
    }

    @Test
    fun onCidToggleOn_writesThenActivates() {
        FeatureFlags.localIpfsCidEnabled = false
        val fragment = launchFragment()
        val cid = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)!!
        cid.isEnabled = true

        callChangeListener(cid, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(FeatureFlags.localIpfsCidEnabled)
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
    }

    @Test
    fun onCidToggle_offOnOffOn_hookCountsStayAtOne_t3() {
        val fragment = launchFragment()
        val cid = fragment.findPreference<SwitchPreferenceCompat>(FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)!!
        cid.isEnabled = true

        callChangeListener(cid, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
        assertEquals(1, ProofArtifactSavedHookRegistry.registeredCountForTests())

        callChangeListener(cid, false)
        shadowOf(Looper.getMainLooper()).idle()

        callChangeListener(cid, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
        assertEquals(1, ProofArtifactSavedHookRegistry.registeredCountForTests())
    }

    private fun callChangeListener(preference: Preference, newValue: Any) {
        val method = Preference::class.java.getDeclaredMethod("callChangeListener", Any::class.java)
        method.isAccessible = true
        method.invoke(preference, newValue)
    }
}
