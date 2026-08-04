package org.witness.proofmode

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import kotlin.system.measureNanoTime
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.plugins.ipfscid.IpfsCidPlugin
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.util.ForegroundWalletActivityBinder
import org.witness.proofmode.util.TestWalletStackHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentalFeatureActivatorTest {

    private lateinit var context: Context
    private lateinit var app: ProofModeApp
    private var applicationScopeJob: Job? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = ProofModeApp::class.java.getDeclaredConstructor().newInstance().also { instance ->
            val attach = android.content.ContextWrapper::class.java.getDeclaredMethod(
                "attachBaseContext",
                Context::class.java,
            )
            attach.isAccessible = true
            attach.invoke(instance, context)
        }
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        FeatureFlags.resetForTests(context)
        clearIpfsCidPluginForTests()
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
        applicationScopeJob?.cancel()
        applicationScopeJob = null
        shadowOf(Looper.getMainLooper()).idle()
        clearIpfsCidPluginForTests()
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

    private fun clearIpfsCidPluginForTests() {
        IpfsCidPlugin.clearRegistrationStateForTests()
    }

    @Test
    fun activationState_startsIdle() {
        assertEquals(LpActivationState.Idle, ExperimentalFeatureActivator.activationState.value)
    }

    @Test
    fun activateLocationProtocol_whenWalletAndEngineReady_reachesReady() {
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true
        LocationProtocolPlugin.registerWalletStack(app)
        LocationProtocolPlugin.setFlutterEngineReadyForTests(true)
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()
        assertEquals(LpActivationState.Ready, ExperimentalFeatureActivator.activationState.value)
    }

    @Test
    fun activateLocalIpfsCid_whenGateOn_attachesHooks() {
        FeatureFlags.localIpfsCidEnabled = true
        ExperimentalFeatureActivator.activateLocalIpfsCid(context)
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
    }

    @Test
    fun activateLocalIpfsCid_whenGateOff_doesNotAttachHooks() {
        FeatureFlags.localIpfsCidEnabled = false
        ExperimentalFeatureActivator.activateLocalIpfsCid(context)
        assertEquals(0, ProofWriteHookRegistry.registeredCountForTests())
    }

    @Test
    fun activateLocationProtocol_registersWalletStack() {
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()
        assertTrue(LocationProtocolPlugin.isWalletStackRegistered())
    }

    @Test
    fun activateLocationProtocol_calledTwice_isIdempotent() {
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()
        assertTrue(LocationProtocolPlugin.isWalletStackRegistered())
    }

    @Test
    fun activateLocationProtocol_whenLpDisabled_doesNotRegisterStack() {
        FeatureFlags.lpEnabled = false
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        assertFalse(LocationProtocolPlugin.isWalletStackRegistered())
    }

    @Test
    fun activateLocationProtocol_walletRestoreUsesRegisteredApplicationScope() = runTest {
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true

        val scopeEntered = CompletableDeferred<Boolean>()
        val trackingJob = SupervisorJob()
        val trackingScope = CoroutineScope(trackingJob + Dispatchers.Unconfined)
        try {
            LocationProtocolPlugin.registerApplicationScope(trackingScope)

            trackingScope.launch {
                scopeEntered.complete(true)
            }

            ExperimentalFeatureActivator.activateLocationProtocol(app)
            advanceUntilIdle()

            assertTrue(
                "Wallet restore should launch via registered application scope",
                scopeEntered.isCompleted,
            )
        } finally {
            trackingJob.cancel()
        }
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
        assertEquals(1, ExperimentalFeatureActivator.walletSessionRestoreLaunchCountForTests.get())
        assertTrue(ForegroundWalletActivityBinder.isRegisteredForTests())

        FeatureFlags.lpEnabled = false
        FeatureFlags.lpEnabled = true
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()

        assertEquals(
            registerCountAfterColdStart,
            LocationProtocolPlugin.getRegisterWalletStackInvocationCountForTests(),
        )
        assertTrue(LocationProtocolPlugin.isWalletStackRegistered())
        assertEquals(1, ExperimentalFeatureActivator.walletSessionRestoreLaunchCountForTests.get())
        assertEquals(LpActivationState.Ready, ExperimentalFeatureActivator.activationState.value)
        assertTrue(ForegroundWalletActivityBinder.isRegisteredForTests())
    }

    @Test
    fun activateLocationProtocol_alreadyRegistered_launchesRestoreOnce() {
        TestWalletStackHelper.setupFakeKeyStore()
        LocationProtocolPlugin.registerWalletStack(app)
        LocationProtocolPlugin.setFlutterEngineReadyForTests(true)
        FeatureFlags.lpEnabled = true

        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()
        assertEquals(1, ExperimentalFeatureActivator.walletSessionRestoreLaunchCountForTests.get())

        ExperimentalFeatureActivator.activateLocationProtocol(app)
        activateAndAwaitIdle()
        assertEquals(1, ExperimentalFeatureActivator.walletSessionRestoreLaunchCountForTests.get())
    }

    @Test
    fun activateLocationProtocol_whenAlreadyReady_registersBinderSynchronously() {
        TestWalletStackHelper.setupFakeKeyStore()
        LocationProtocolPlugin.registerWalletStack(app)
        LocationProtocolPlugin.setFlutterEngineReadyForTests(true)
        FeatureFlags.lpEnabled = true

        ExperimentalFeatureActivator.activateLocationProtocol(app)

        assertTrue(
            "W7: binder must register before async job runs",
            ForegroundWalletActivityBinder.isRegisteredForTests(),
        )
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
        // Measured on Robolectric/SDK 28 with fake Keystore: registerWalletStack runs off Main;
        // synchronous activateLocationProtocol entry on Main is typically <50ms before the Default job
        // (Robolectric JVM variance; device path is tighter). FlutterEngine init still runs on Main
        // inside the job (NG12 — not removed).
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true
        val activateEntryNanos = measureNanoTime {
            ExperimentalFeatureActivator.activateLocationProtocol(app)
        }
        activateAndAwaitIdle()
        assertEquals(
            false,
            LocationProtocolPlugin.wasRegisterWalletStackInvokedOnMainThreadForTests(),
        )
        assertTrue(
            "LP activate entry should stay within main-thread budget before async IO",
            activateEntryNanos < 50_000_000,
        )
    }
}
