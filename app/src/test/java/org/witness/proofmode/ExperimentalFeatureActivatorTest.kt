package org.witness.proofmode

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.plugins.ipfscid.IpfsCidPlugin
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.util.ForegroundWalletActivityBinder
import org.witness.proofmode.util.TestWalletStackHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ExperimentalFeatureActivatorTest {

    private lateinit var context: Context
    private lateinit var app: ProofModeApp
    private var applicationScopeJob: Job? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = LocationSettingsActivityTestSupport.reflectiveProofModeApp(context)
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        FeatureFlags.resetForTests(context)
        IpfsCidPlugin.clearRegistrationStateForTests()
        ForegroundWalletActivityBinder.resetRegistrationStateForTests()
        TestWalletStackHelper.resetWalletStack()
        applicationScopeJob?.cancel()
        applicationScopeJob = SupervisorJob()
        LocationProtocolPlugin.registerApplicationScope(CoroutineScope(applicationScopeJob!! + Dispatchers.Unconfined))
        ExperimentalFeatureActivator.resetActivationStateForTests()
        ExperimentalFeatureActivator.resetActivationGuardsForTests()
        LocationProtocolPlugin.resetRegisterWalletStackInvocationCountForTests()
    }

    @After
    fun tearDown() {
        LocationSettingsActivityTestSupport.tearDown()
        applicationScopeJob?.cancel()
        applicationScopeJob = null
        shadowOf(Looper.getMainLooper()).idle()
        IpfsCidPlugin.clearRegistrationStateForTests()
        ForegroundWalletActivityBinder.resetRegistrationStateForTests()
        TestWalletStackHelper.resetWalletStack()
        LocationProtocolPlugin.setFlutterEngineReadyForTests(null)
        LocationProtocolPlugin.resetRegisterWalletStackInvocationCountForTests()
        ExperimentalFeatureActivator.resetActivationGuardsForTests()
    }

    private fun activateAndAwaitIdle() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            when (ExperimentalFeatureActivator.activationState.value) {
                LpActivationState.Ready, LpActivationState.Failed -> return
                else -> Thread.sleep(5)
            }
        }
    }

    @Test
    fun activateLocationProtocol_whenLpDisabled_doesNotRegisterStack() {
        FeatureFlags.lpEnabled = false
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        assertFalse(LocationProtocolPlugin.isWalletStackRegistered())
        assertEquals(0, LocationProtocolPlugin.getRegisterWalletStackInvocationCountForTests())
    }

    @Test
    fun activateLocationProtocol_readyOffOn_doesNotReRegisterOrRestoreTwice() {
        TestWalletStackHelper.setupFakeKeyStore()
        LocationProtocolPlugin.registerWalletStack(app)
        LocationProtocolPlugin.setFlutterEngineReadyForTests(true)
        val registerCountAfterColdStart = LocationProtocolPlugin.getRegisterWalletStackInvocationCountForTests()
        FeatureFlags.lpEnabled = true

        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()
        assertEquals(LpActivationState.Ready, ExperimentalFeatureActivator.activationState.value)
        assertTrue(ForegroundWalletActivityBinder.isRegisteredForTests())

        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()

        assertEquals(
            registerCountAfterColdStart,
            LocationProtocolPlugin.getRegisterWalletStackInvocationCountForTests(),
        )
        assertEquals(LpActivationState.Ready, ExperimentalFeatureActivator.activationState.value)
        assertTrue(ForegroundWalletActivityBinder.isRegisteredForTests())
    }

    @Test
    fun bootstrapAtColdStart_withLpEnabled_activatesProtocol() {
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true
        LocationProtocolPlugin.setFlutterEngineReadyForTests(true)
        ExperimentalFeatureActivator.bootstrapAtColdStart(app)
        activateAndAwaitIdle()
        assertTrue(LocationProtocolPlugin.isWalletStackRegistered())
    }

    @Test
    fun activateLocationProtocol_registerOkEngineFail_selfHealsOnRetry_t13() {
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true
        LocationProtocolPlugin.setFlutterEngineReadyForTests(false)

        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()

        assertEquals(LpActivationState.Failed, ExperimentalFeatureActivator.activationState.value)
        assertTrue(LocationProtocolPlugin.isWalletStackRegistered())
        assertFalse(LocationProtocolPlugin.isFlutterEngineReady())
        assertTrue(FeatureFlags.lpEnabled)
        val registerCountAfterFirst = LocationProtocolPlugin.getRegisterWalletStackInvocationCountForTests()
        assertEquals(1, registerCountAfterFirst)

        LocationProtocolPlugin.setFlutterEngineReadyForTests(true)
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()

        assertEquals(LpActivationState.Ready, ExperimentalFeatureActivator.activationState.value)
        assertEquals(
            registerCountAfterFirst,
            LocationProtocolPlugin.getRegisterWalletStackInvocationCountForTests(),
        )
    }

    @Test
    fun launchWalletSessionRestore_doesNotReferenceGlobalScope() {
        val source = java.io.File("src/main/java/org/witness/proofmode/ExperimentalFeatureActivator.kt")
            .readText()
        assertFalse(
            "ExperimentalFeatureActivator must not launch wallet restore on GlobalScope",
            source.contains("GlobalScope.launch"),
        )
    }

    @Test
    fun activateLocationProtocol_keystoreWorkRunsOffMain_t9() {
        // registerWalletStack must run off Main. FlutterEngine init still runs on Main inside the job
        // (kept there on purpose: the engine needs the main thread).
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()
        assertEquals(
            false,
            LocationProtocolPlugin.wasRegisterWalletStackInvokedOnMainThreadForTests(),
        )
    }
}
