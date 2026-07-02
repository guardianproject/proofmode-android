package org.witness.proofmode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.plugins.ipfscid.IpfsCidPlugin
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.util.TestWalletStackHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class DeveloperPreviewHotActivationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        FeatureFlags.resetForTests(context)
        IpfsCidPlugin.clearRegistrationStateForTests()
        TestWalletStackHelper.resetWalletStack()
        LocationProtocolPlugin.registerApplicationScope(CoroutineScope(SupervisorJob()))
    }

    @After
    fun tearDown() {
        IpfsCidPlugin.clearRegistrationStateForTests()
        TestWalletStackHelper.resetWalletStack()
    }

    @Test
    fun onCidToggleOn_invokesHotActivation() {
        FeatureFlags.localIpfsCidEnabled = true
        ExperimentalFeatureActivator.activateLocalIpfsCid(context)
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
    }

    @Test
    fun onLpToggleOn_invokesHotActivation() {
        TestWalletStackHelper.setupFakeKeyStore()
        val app = ProofModeApp::class.java.getDeclaredConstructor().newInstance().also { instance ->
            val attach = android.content.ContextWrapper::class.java.getDeclaredMethod(
                "attachBaseContext",
                Context::class.java,
            )
            attach.isAccessible = true
            attach.invoke(instance, context)
        }
        FeatureFlags.lpEnabled = true
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        assertTrue(LocationProtocolPlugin.isWalletStackRegistered())
    }
}
