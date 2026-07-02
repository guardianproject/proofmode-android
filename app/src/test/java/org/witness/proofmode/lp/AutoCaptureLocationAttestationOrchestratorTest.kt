package org.witness.proofmode.lp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atMost
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationCoordinator
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationResult
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.autocapture.LpRunState
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.StorageProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoCaptureLocationAttestationOrchestratorTest {

    private lateinit var context: Context
    private var activeHandle: AutoCaptureLocationAttestationOrchestrator.TestOrchestratorHandle? = null
    private var applicationScopeJob: Job? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        FeatureFlags.init(context)
        applicationScopeJob?.cancel()
        applicationScopeJob = SupervisorJob()
        LocationProtocolPlugin.registerApplicationScope(
            CoroutineScope(applicationScopeJob!! + Dispatchers.Unconfined),
        )
        AutoCaptureLpStateRegistry.clearForTests()
        AutoCaptureLocationAttestationOrchestrator.resetForTests()
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests(enqueueNoOpForTests)
    }

    @After
    fun tearDown() {
        activeHandle?.shutdownForTests()
        activeHandle = null
        AutoCaptureLocationAttestationOrchestrator.installSkipListener(null)
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests(null)
        AutoCaptureLocationAttestationOrchestrator.resetForTests()
        applicationScopeJob?.cancel()
        applicationScopeJob = null
        AutoCaptureLpStateRegistry.clearForTests()
    }

    private fun trackHandle(
        handle: AutoCaptureLocationAttestationOrchestrator.TestOrchestratorHandle,
    ): AutoCaptureLocationAttestationOrchestrator.TestOrchestratorHandle {
        activeHandle?.shutdownForTests()
        activeHandle = handle
        return handle
    }

    private fun TestScope.createOrchestratorHandle(
        coordinatorFactory: (StorageProvider, Context) -> LocationProtocolAttestationCoordinator,
        proofExistsDelayMs: Map<String, Long> = emptyMap(),
        storageFactory: ((Context) -> StorageProvider)? = null,
        testGuards: OrchestratorTestGuards = OrchestratorTestGuards(),
        walletActivityWaitTimeoutMs: Long =
            AutoCaptureLocationAttestationOrchestrator.WALLET_ACTIVITY_WAIT_TIMEOUT_MS,
        autoStart: Boolean = true,
    ): AutoCaptureLocationAttestationOrchestrator.TestOrchestratorHandle {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return trackHandle(
            AutoCaptureLocationAttestationOrchestrator.createForTests(
                context = context,
                coordinatorFactory = coordinatorFactory,
                proofExistsDelayMs = proofExistsDelayMs,
                storageFactory = storageFactory,
                testGuards = testGuards,
                walletActivityWaitTimeoutMs = walletActivityWaitTimeoutMs,
                autoStart = autoStart,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                processorDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ),
        )
    }

    @Test
    fun autoCaptureJob_defaultsManualLegToNull() {
        val job = AutoCaptureJob(
            mediaHash = "hash1",
            mediaUri = android.net.Uri.parse("content://test/1"),
        )
        assertNull(job.manualLeg)
    }

    @Test
    fun enqueue_whenLpDisabled_doesNotQueue() {
        FeatureFlags.lpEnabled = false
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN

        AutoCaptureLocationAttestationOrchestrator.enqueue(
            context,
            android.net.Uri.parse("content://test/1"),
            "hash1",
        )

        assertEquals(0, AutoCaptureLocationAttestationOrchestrator.pendingCountForTests())
    }

    @Test
    fun enqueue_whenModeOff_doesNotQueue() {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF

        AutoCaptureLocationAttestationOrchestrator.enqueue(
            context,
            android.net.Uri.parse("content://test/1"),
            "hash1",
        )

        assertEquals(0, AutoCaptureLocationAttestationOrchestrator.pendingCountForTests())
    }

    @Test
    fun processor_waitsForProofExistsBeforeCoordinator() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = mock<StorageProvider>()
        whenever(storage.proofExists("hash1")).thenReturn(false)

        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> coordinator },
            proofExistsDelayMs = mapOf("hash1" to 50L),
            storageFactory = { storage },
        )
        handle.enqueueTestJob("hash1")
        advanceTimeBy(51)
        verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
    }

    @Test
    fun enqueueManual_whenLpDisabled_doesNotQueue() {
        FeatureFlags.lpEnabled = false
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF

        AutoCaptureLocationAttestationOrchestrator.enqueueManual(
            context,
            android.net.Uri.parse("content://test/1"),
            "hash1",
            LpManualLeg.OFFCHAIN,
        )

        assertEquals(0, AutoCaptureLocationAttestationOrchestrator.pendingCountForTests())
    }

    @Test
    fun offerManual_whenAutoCaptureModeOff_stillQueuesBeforeProcessorStarts() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF

        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> mock() },
            autoStart = false,
        )
        handle.offerManualForTests(
            mediaHash = "hash1",
            leg = LpManualLeg.OFFCHAIN,
        )

        assertEquals(1, handle.pendingCountForTests())
    }

    @Test
    fun enqueueManual_whenLpEnabled_doesNotCrashWithRegisteredScope() {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF

        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests(null)
        try {
            AutoCaptureLocationAttestationOrchestrator.enqueueManual(
                context,
                android.net.Uri.parse("content://test/1"),
                "hash1",
                LpManualLeg.OFFCHAIN,
            )
        } finally {
            AutoCaptureLocationAttestationOrchestrator.resetForTests()
            AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests(enqueueNoOpForTests)
        }
    }

    @Test
    fun enqueueTestJob_withManualLeg_preservesLegOnQueuedJob() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        whenever(coordinator.attestOffchain(any(), any(), any(), any()))
            .thenReturn(Result.success(mock<LocationProtocolAttestationResult>()))

        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        handle.enqueueTestJob("hash1", manualLeg = LpManualLeg.OFFCHAIN)
        advanceUntilIdle()

        verify(coordinator).attestOffchain(eq("hash1"), any(), any(), any())
        verify(coordinator, never()).attestOnchain(any(), any(), any(), any(), any())
    }

    @Test
    fun processJob_autoCapturePath_stillSkippedWhenModeOff() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")

        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        handle.enqueueTestJob("hash1", manualLeg = null)
        advanceUntilIdle()

        verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
    }

    @Test
    fun manualOffchainJob_walletUnavailable_marksOffchainSkippedOnly() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")

        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            testGuards = OrchestratorTestGuards(walletConnected = false),
        )
        handle.enqueueTestJob("hash1", manualLeg = LpManualLeg.OFFCHAIN)
        advanceUntilIdle()

        val state = AutoCaptureLpStateRegistry.getState("hash1")
        assertEquals(LpRunState.SKIPPED, state.offchain)
        assertEquals(LpRunState.IDLE, state.onchain)
        verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
    }

    @Test
    fun manualOnchainJob_waitsForWalletActivity_thenAttests() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        whenever(coordinator.attestOnchain(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(mock()))

        var bound = false
        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            testGuards = OrchestratorTestGuards(
                walletActivityBoundProvider = { bound },
            ),
        )
        handle.enqueueTestJob("hash1", manualLeg = LpManualLeg.ONCHAIN)

        advanceTimeBy(50)
        var state = AutoCaptureLpStateRegistry.getState("hash1")
        assertEquals(LpRunState.IDLE, state.onchain)
        verify(coordinator, never()).attestOnchain(any(), any(), any(), any(), any())

        bound = true
        advanceUntilIdle()

        state = AutoCaptureLpStateRegistry.getState("hash1")
        assertEquals(LpRunState.SUCCEEDED, state.onchain)
        verify(coordinator).attestOnchain(eq("hash1"), any(), any(), any(), any())
    }

    @Test
    fun manualOnchainJob_walletActivityTimeout_marksSkippedAndDoesNotAttest() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        val skipReasons = mutableListOf<AutoCaptureSkipReason>()
        AutoCaptureLocationAttestationOrchestrator.installSkipListener { skipReasons.add(it) }

        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            testGuards = OrchestratorTestGuards(walletActivityBoundProvider = { false }),
            walletActivityWaitTimeoutMs = 300L,
        )
        handle.enqueueTestJob("hash1", manualLeg = LpManualLeg.ONCHAIN)
        advanceUntilIdle()

        val state = AutoCaptureLpStateRegistry.getState("hash1")
        assertEquals(LpRunState.SKIPPED, state.onchain)
        verify(coordinator, never()).attestOnchain(any(), any(), any(), any(), any())
        assertEquals(listOf(AutoCaptureSkipReason.NO_FOREGROUND_ACTIVITY), skipReasons)
    }

    @Test
    fun fifo_processesAutoCaptureThenManualJobsInArrivalOrder() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN
        val callOrder = mutableListOf<String>()
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        whenever(coordinator.attestOffchain(any(), any(), any(), any())).thenAnswer {
            callOrder.add(it.getArgument(0))
            Result.success(mock<LocationProtocolAttestationResult>())
        }
        val storage = OrchestratorTestStorage()
        storage.seedProof("hashA")
        storage.seedProof("hashB")
        storage.seedProof("hashC")

        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        handle.enqueueTestJob("hashA", manualLeg = null)
        handle.enqueueTestJob("hashB", manualLeg = LpManualLeg.OFFCHAIN)
        handle.enqueueTestJob("hashC", manualLeg = LpManualLeg.OFFCHAIN)
        advanceUntilIdle()

        assertEquals(listOf("hashA", "hashB", "hashC"), callOrder)
    }

    @Test
    fun fifo_manualOnchainJobs_deferredUntilBindThenAllAttest() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        whenever(coordinator.attestOffchain(any(), any(), any(), any()))
            .thenReturn(Result.success(mock()))
        whenever(coordinator.attestOnchain(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(mock()))
        val storage = OrchestratorTestStorage()
        storage.seedProof("hashA")
        storage.seedProof("hashB")
        storage.seedProof("hashC")

        var bound = true
        val handle = createOrchestratorHandle(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            testGuards = OrchestratorTestGuards(walletActivityBoundProvider = { bound }),
        )
        handle.enqueueTestJob("hashA", manualLeg = LpManualLeg.ONCHAIN)
        bound = false
        handle.enqueueTestJob("hashB", manualLeg = LpManualLeg.ONCHAIN)
        handle.enqueueTestJob("hashC", manualLeg = LpManualLeg.ONCHAIN)

        advanceTimeBy(50)
        verify(coordinator, atMost(1)).attestOnchain(any(), any(), any(), any(), any())

        bound = true
        advanceUntilIdle()

        verify(coordinator, times(3)).attestOnchain(any(), any(), any(), any(), any())
        assertEquals(LpRunState.SUCCEEDED, AutoCaptureLpStateRegistry.getState("hashB").onchain)
        assertEquals(LpRunState.SUCCEEDED, AutoCaptureLpStateRegistry.getState("hashC").onchain)
    }

    companion object {
        private val enqueueNoOpForTests: (Context, android.net.Uri, String) -> Unit = { _, _, _ -> }
    }
}
