package org.witness.proofmode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.ipfscid.LocalIpfsCidGate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class FeatureFlagsLpCidCouplingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        FeatureFlags.resetForTests(context)
    }

    @Test
    fun cidDefaultRemainsFalse_andKeyAliasUnchanged() {
        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertEquals(LocalIpfsCidGate.KEY_ENABLED, FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)
    }

    @Test
    fun sentinelsDefaultFalse_absentKeys() {
        val prefs = context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(prefs.contains(FeatureFlags.KEY_LOCAL_IPFS_CID_USER_SET))
        assertFalse(prefs.contains(FeatureFlags.KEY_LP_CID_AUTO_COUPLING_APPLIED))
        assertFalse(FeatureFlags.localIpfsCidUserSet)
        assertFalse(FeatureFlags.lpCidAutoCouplingApplied)
    }

    @Test
    fun localIpfsCidUserSet_persistsIndependentlyOfCidEnabled() {
        FeatureFlags.localIpfsCidUserSet = true
        assertTrue(FeatureFlags.localIpfsCidUserSet)
        assertFalse(FeatureFlags.localIpfsCidEnabled)
        FeatureFlags.localIpfsCidEnabled = true
        assertTrue(FeatureFlags.localIpfsCidUserSet)
        assertTrue(FeatureFlags.localIpfsCidEnabled)
    }

    @Test
    fun lpCidAutoCouplingApplied_persistsIndependently() {
        FeatureFlags.lpCidAutoCouplingApplied = true
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)
        assertFalse(FeatureFlags.localIpfsCidEnabled)
        assertFalse(FeatureFlags.localIpfsCidUserSet)
    }

    @Test
    fun applyLpCidAutoEnable_setsCidTrueAndCoupling_withoutUserSet() {
        FeatureFlags.applyLpCidAutoEnable()
        assertTrue(FeatureFlags.localIpfsCidEnabled)
        assertTrue(FeatureFlags.lpCidAutoCouplingApplied)
        assertFalse(FeatureFlags.localIpfsCidUserSet)
    }
}
