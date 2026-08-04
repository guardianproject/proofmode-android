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
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class FeatureFlagsLocalIpfsCidTest {
    private lateinit var context: Context
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        FeatureFlags.resetForTests(context)
        logs.clear()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs.add(message)
            }
        })
    }

    @Test
    fun localIpfsCidEnabled_defaultsFalse() {
        assertFalse(FeatureFlags.localIpfsCidEnabled)
    }

    @Test
    fun localIpfsCidEnabled_usesSameKeyAsGate() {
        assertEquals(LocalIpfsCidGate.KEY_ENABLED, FeatureFlags.KEY_LOCAL_IPFS_CID_ENABLED)
    }

    @Test
    fun localIpfsCidEnabled_roundTripVisibleToGate() {
        FeatureFlags.localIpfsCidEnabled = true
        assertEquals(true, LocalIpfsCidGate.isEnabled(context))
    }

    @Test
    fun localIpfsCidEnabled_setter_doesNotLogRestartRequirementOnEnable() {
        FeatureFlags.localIpfsCidEnabled = true
        assertFalse(logs.any { it.contains("requires app restart") })
    }

    @Test
    fun lpEnabled_setter_logsDisableRestartNoteWhenTurningOff() {
        FeatureFlags.lpEnabled = true
        logs.clear()
        FeatureFlags.lpEnabled = false
        assertTrue(logs.any { it.contains("disable may require app restart") })
    }
}
