package org.witness.proofmode

import android.content.Context
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
import org.robolectric.annotation.Config
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
        LocationProtocolPlugin.registerApplicationScope(CoroutineScope(applicationScopeJob!!))
    }

    @After
    fun tearDown() {
        applicationScopeJob?.cancel()
        applicationScopeJob = null
        clearIpfsCidPluginForTests()
        ForegroundWalletActivityBinder.resetRegistrationStateForTests()
        TestWalletStackHelper.resetWalletStack()
    }

    private fun clearIpfsCidPluginForTests() {
        IpfsCidPlugin.clearRegistrationStateForTests()
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
        assertTrue(LocationProtocolPlugin.isWalletStackRegistered())
    }

    @Test
    fun activateLocationProtocol_calledTwice_isIdempotent() {
        TestWalletStackHelper.setupFakeKeyStore()
        FeatureFlags.lpEnabled = true
        ExperimentalFeatureActivator.activateLocationProtocol(app)
        ExperimentalFeatureActivator.activateLocationProtocol(app)
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
    fun launchWalletSessionRestore_doesNotReferenceGlobalScope() {
        val source = java.io.File("src/main/java/org/witness/proofmode/ExperimentalFeatureActivator.kt")
            .readText()
        assertFalse(
            "ExperimentalFeatureActivator must not launch wallet restore on GlobalScope",
            source.contains("GlobalScope.launch"),
        )
    }
}
