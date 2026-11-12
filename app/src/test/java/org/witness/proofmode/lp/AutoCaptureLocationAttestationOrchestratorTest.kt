package org.witness.proofmode.lp

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.ProofMode
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationCoordinator
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationResult
import org.witness.proofmode.plugins.lp.attestation.PendingOnchainRecheckOutcome
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.autocapture.LpRunState
import org.witness.proofmode.storage.StorageProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoCaptureLocationAttestationOrchestratorTest {

    private lateinit var context: Context
    private var activeEngine: OrchestratorEngine? = null
    private var applicationScopeJob: Job? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        FeatureFlags.resetForTests(context)
        primeLocationMasterOn()
        applicationScopeJob?.cancel()
        applicationScopeJob = SupervisorJob()
        LocationProtocolPlugin.registerApplicationScope(
            CoroutineScope(applicationScopeJob!! + Dispatchers.Unconfined),
        )
        AutoCaptureLpStateRegistry.clearForTests()
        AutoCaptureLocationAttestationOrchestrator.resetForTests()
    }

    @After
    fun tearDown() {
        activeEngine?.shutdown()
        activeEngine = null
        AutoCaptureLocationAttestationOrchestrator.resetForTests()
        applicationScopeJob?.cancel()
        applicationScopeJob = null
        AutoCaptureLpStateRegistry.clearForTests()
    }

    private fun primeLocationMasterOn() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, true).commit()
    }

    private fun primeLocationMasterOff() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_LOCATION, false).commit()
    }

    private fun primeLpActiveEnabled() {
        primeLocationMasterOn()
        FeatureFlags.lpEnabled = true
    }

    private fun TestScope.createEngine(
        coordinatorFactory: (StorageProvider, Context) -> LocationProtocolAttestationCoordinator,
        storageFactory: ((Context) -> StorageProvider)? = null,
        isWalletConnected: () -> Boolean = { true },
        awaitWalletActivityBound: suspend () -> Boolean = { true },
        skipListener: AutoCaptureSkipListener? = null,
        autoStart: Boolean = true,
    ): OrchestratorEngine {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        activeEngine?.shutdown()
        val engine = OrchestratorEngine(
            appContext = context.applicationContext,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            coordinatorFactory = coordinatorFactory,
            storageFactory = storageFactory,
            isWalletConnected = isWalletConnected,
            awaitWalletActivityBound = awaitWalletActivityBound,
            processorDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            skipListenerProvider = { skipListener },
        )
        if (autoStart) engine.startProcessor()
        activeEngine = engine
        return engine
    }

    private fun OrchestratorEngine.offer(
        mediaHash: String,
        manualLeg: LpManualLeg? = null,
        onComplete: ((Boolean) -> Unit)? = null,
    ) = offerDirect(Uri.parse("content://test/$mediaHash"), mediaHash, manualLeg, onComplete)

    @Test
    fun offerDirect_manualLeg_whenAutoCaptureModeOff_stillQueuesBeforeProcessorStarts() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF

        val engine = createEngine(
            coordinatorFactory = { _, _ -> mock() },
            autoStart = false,
        )
        engine.offer("hash1", manualLeg = LpManualLeg.OFFCHAIN)

        assertEquals(1, engine.pendingCount())
    }

    @Test
    fun enqueueManual_whenLpEnabled_doesNotCrashWithRegisteredScope() {
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests(null)
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF

        AutoCaptureLocationAttestationOrchestrator.enqueueManual(
            context,
            Uri.parse("content://test/1"),
            "hash1",
            LpManualLeg.OFFCHAIN,
            onComplete = {},
        )
    }

    @Test
    fun processJob_autoCapturePath_stillSkippedWhenModeOff() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        engine.offer("hash1", manualLeg = null)
        advanceUntilIdle()

        verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
    }

    @Test
    fun manualOffchainJob_walletUnavailable_doesNotAttest() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        val reports = mutableListOf<Boolean>()

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            isWalletConnected = { false },
        )
        engine.offer("hash1", manualLeg = LpManualLeg.OFFCHAIN, onComplete = { reports += it })
        advanceUntilIdle()

        val state = AutoCaptureLpStateRegistry.getState("hash1")
        assertEquals(LpRunState.IDLE, state.offchain)
        assertEquals(LpRunState.IDLE, state.onchain)
        verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
        assertEquals(listOf(false), reports)
    }

    @Test
    fun autoCaptureJob_walletUnavailable_notifiesAutoWalletReason() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.BOTH
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        val skipReasons = mutableListOf<AutoCaptureSkipReason>()

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            isWalletConnected = { false },
            skipListener = { skipReasons.add(it) },
        )
        engine.offer("hash1")
        advanceUntilIdle()

        verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
        verify(coordinator, never()).attestOnchain(any(), any(), any(), any(), any(), any())
        assertEquals(listOf(AutoCaptureSkipReason.AUTO_CAPTURE_WALLET_UNAVAILABLE), skipReasons)
    }

    @Test
    fun `autoCaptureJob_invalidLocation_skipsBothLegsWithoutAttesting`() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.BOTH
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1", latitude = 0.0, longitude = 0.0)
        val skipReasons = mutableListOf<AutoCaptureSkipReason>()

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            skipListener = { skipReasons.add(it) },
        )
        engine.offer("hash1")
        advanceUntilIdle()

        val state = AutoCaptureLpStateRegistry.getState("hash1")
        assertEquals(LpRunState.IDLE, state.offchain)
        assertEquals(LpRunState.IDLE, state.onchain)
        verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
        verify(coordinator, never()).attestOnchain(any(), any(), any(), any(), any(), any())
        assertEquals(listOf(AutoCaptureSkipReason.LOCATION_UNAVAILABLE), skipReasons)
    }

    @Test
    fun `autoCaptureJob_acceptsValidSingleZeroCoordinateAxis`() = runTest {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        whenever(coordinator.attestOffchain(any(), any(), any(), any()))
            .thenReturn(Result.success(mock<LocationProtocolAttestationResult>()))
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1", latitude = 0.0, longitude = 30.0)

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        engine.offer("hash1")
        advanceUntilIdle()

        assertEquals(LpRunState.SUCCEEDED, AutoCaptureLpStateRegistry.getState("hash1").offchain)
        verify(coordinator).attestOffchain(eq("hash1"), any(), any(), any())
    }

    @Test
    fun manualOnchainJob_waitsForWalletActivity_thenAttests() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        whenever(coordinator.attestOnchain(any(), any(), any(), any(), any(), any())).thenAnswer {
            storage.seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}")
            Result.success(mock<LocationProtocolAttestationResult>())
        }

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            awaitWalletActivityBound = { true },
        )
        engine.offer("hash1", manualLeg = LpManualLeg.ONCHAIN)
        advanceUntilIdle()

        assertEquals(LpRunState.SUCCEEDED, AutoCaptureLpStateRegistry.getState("hash1").onchain)
        verify(coordinator).attestOnchain(eq("hash1"), any(), any(), any(), any(), any())
    }

    @Test
    fun manualOnchainJob_walletActivityTimeout_doesNotAttest() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        val skipReasons = mutableListOf<AutoCaptureSkipReason>()
        val reports = mutableListOf<Boolean>()

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
            awaitWalletActivityBound = { false },
            skipListener = { skipReasons.add(it) },
        )
        engine.offer("hash1", manualLeg = LpManualLeg.ONCHAIN, onComplete = { reports += it })
        advanceUntilIdle()

        val state = AutoCaptureLpStateRegistry.getState("hash1")
        assertEquals(LpRunState.IDLE, state.onchain)
        verify(coordinator, never()).attestOnchain(any(), any(), any(), any(), any(), any())
        assertEquals(listOf(AutoCaptureSkipReason.NO_FOREGROUND_ACTIVITY), skipReasons)
        assertEquals(listOf(false), reports)
    }

    @Test
    fun manualJob_reportsOutcomeOncePerLeg() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val success = Result.success(mock<LocationProtocolAttestationResult>())
        val failure = Result.failure<LocationProtocolAttestationResult>(IllegalStateException("failed"))
        // Every hash stubs both legs, the other leg with the opposite result, so a job sent to the
        // wrong leg reports the wrong value instead of passing on an unstubbed mock.
        whenever(coordinator.attestOffchain(eq("offchainOk"), any(), any(), any())).thenReturn(success)
        whenever(coordinator.attestOnchain(eq("offchainOk"), any(), any(), any(), any(), any())).thenReturn(failure)
        whenever(coordinator.attestOnchain(eq("onchainOk"), any(), any(), any(), any(), any())).thenReturn(success)
        whenever(coordinator.attestOffchain(eq("onchainOk"), any(), any(), any())).thenReturn(failure)
        whenever(coordinator.attestOffchain(eq("offchainFail"), any(), any(), any())).thenReturn(failure)
        whenever(coordinator.attestOnchain(eq("offchainFail"), any(), any(), any(), any(), any())).thenReturn(success)
        whenever(coordinator.attestOnchain(eq("onchainFail"), any(), any(), any(), any(), any())).thenReturn(failure)
        whenever(coordinator.attestOffchain(eq("onchainFail"), any(), any(), any())).thenReturn(success)
        val storage = OrchestratorTestStorage()
        listOf("offchainOk", "onchainOk", "offchainFail", "onchainFail").forEach { storage.seedProof(it) }
        val reports = HashMap<String, MutableList<Boolean>>()
        fun reportTo(hash: String): (Boolean) -> Unit = { reports.getOrPut(hash) { mutableListOf() } += it }

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        engine.offer("offchainOk", manualLeg = LpManualLeg.OFFCHAIN, onComplete = reportTo("offchainOk"))
        engine.offer("onchainOk", manualLeg = LpManualLeg.ONCHAIN, onComplete = reportTo("onchainOk"))
        engine.offer("offchainFail", manualLeg = LpManualLeg.OFFCHAIN, onComplete = reportTo("offchainFail"))
        engine.offer("onchainFail", manualLeg = LpManualLeg.ONCHAIN, onComplete = reportTo("onchainFail"))
        advanceUntilIdle()

        assertEquals("offchain ok", listOf(true), reports["offchainOk"])
        assertEquals("onchain ok", listOf(true), reports["onchainOk"])
        assertEquals("offchain fail", listOf(false), reports["offchainFail"])
        assertEquals("onchain fail", listOf(false), reports["onchainFail"])
    }

    @Test
    fun manualJob_existingArtifact_reportsOkWithoutAttesting() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("offchain")
        storage.seedArtifactIdentifier("offchain", "offchain${LocationProtocolArtifactStore.OFFCHAIN_SUFFIX}")
        storage.seedProof("confirmed")
        storage.seedArtifactIdentifier("confirmed", "confirmed${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}")
        val reports = mutableListOf<Boolean>()

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        engine.offer("offchain", manualLeg = LpManualLeg.OFFCHAIN, onComplete = { reports += it })
        engine.offer("confirmed", manualLeg = LpManualLeg.ONCHAIN, onComplete = { reports += it })
        advanceUntilIdle()

        assertEquals(listOf(true, true), reports)
        verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
        verify(coordinator, never()).attestOnchain(any(), any(), any(), any(), any(), any())
    }

    private class PendingOnlyLegRun(
        val coordinator: LocationProtocolAttestationCoordinator,
        val reports: List<Boolean>,
    )

    /** Runs a manual onchain job for a proof holding only a pending record whose re-check finds [recheck]. */
    private suspend fun TestScope.runPendingOnlyOnchainLeg(recheck: PendingOnchainRecheckOutcome): PendingOnlyLegRun {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        whenever(coordinator.recheckPendingOnchain("hash1")).thenReturn(recheck)
        whenever(coordinator.attestOnchain(any(), any(), any(), any(), any(), any()))
            .thenReturn(Result.success(mock()))
        val storage = OrchestratorTestStorage().apply {
            seedProof("hash1")
            seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX}")
        }
        val reports = mutableListOf<Boolean>()

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        engine.offer("hash1", manualLeg = LpManualLeg.ONCHAIN, onComplete = { reports += it })
        advanceUntilIdle()
        return PendingOnlyLegRun(coordinator, reports)
    }

    @Test
    fun onchainLeg_pendingOnly_confirmed_succeeds_withoutAttesting() = runTest {
        val run = runPendingOnlyOnchainLeg(PendingOnchainRecheckOutcome.Confirmed(mock()))

        assertEquals(LpRunState.SUCCEEDED, AutoCaptureLpStateRegistry.getState("hash1").onchain)
        verify(run.coordinator, never()).attestOnchain(any(), any(), any(), any(), any(), any())
        assertEquals(listOf(true), run.reports)
    }

    @Test
    fun onchainLeg_pendingOnly_resubmit_attestsAsFresh() = runTest {
        val run = runPendingOnlyOnchainLeg(PendingOnchainRecheckOutcome.Resubmit)

        verify(run.coordinator).attestOnchain(eq("hash1"), any(), any(), any(), any(), any())
    }

    @Test
    fun onchainLeg_pendingOnly_stillPending_skips() = runTest {
        val run = runPendingOnlyOnchainLeg(PendingOnchainRecheckOutcome.StillPending)

        assertEquals(LpRunState.SKIPPED, AutoCaptureLpStateRegistry.getState("hash1").onchain)
        verify(run.coordinator, never()).attestOnchain(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun onchainLeg_confirmationFailureCallback_marksLegFailed() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        whenever(coordinator.attestOnchain(any(), any(), any(), any(), any(), any())).thenAnswer {
            // Submit saved the pending record; the confirmation outcome arrives later.
            storage.seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX}")
            Result.success(mock<LocationProtocolAttestationResult>())
        }

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        engine.offer("hash1", manualLeg = LpManualLeg.ONCHAIN)
        advanceUntilIdle()
        assertEquals(LpRunState.RUNNING, AutoCaptureLpStateRegistry.getState("hash1").onchain)

        val onchainFailed = argumentCaptor<(String) -> Unit>()
        verify(coordinator).attestOnchain(
            mediaHash = any(),
            mediaUri = any(),
            context = any(),
            memo = any(),
            onchainConfirmed = any(),
            onchainFailed = onchainFailed.capture(),
        )
        onchainFailed.firstValue.invoke("hash1")

        assertEquals(LpRunState.FAILED, AutoCaptureLpStateRegistry.getState("hash1").onchain)
    }

    private class NotReadyRow(
        val name: String,
        val leg: LpManualLeg,
        val seed: (OrchestratorTestStorage) -> Unit,
        val afterSeed: () -> Unit = {},
    )

    @Test
    fun manualJob_notReady_reportsFailedOnce() = runTest {
        val rows = listOf(
            NotReadyRow("locationUnavailable", LpManualLeg.ONCHAIN, { it.seedProof("hash1", latitude = 0.0, longitude = 0.0) }),
            NotReadyRow("proofSidecarTimeout", LpManualLeg.OFFCHAIN, { }),
            NotReadyRow("lpDisabledAtRun", LpManualLeg.OFFCHAIN, { it.seedProof("hash1") }, ::primeLocationMasterOff),
        )
        rows.forEach { row ->
            primeLpActiveEnabled()
            FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
            val coordinator = mock<LocationProtocolAttestationCoordinator>()
            val storage = OrchestratorTestStorage().also(row.seed)
            row.afterSeed()
            val reports = mutableListOf<Boolean>()

            val engine = createEngine(
                coordinatorFactory = { _, _ -> coordinator },
                storageFactory = { storage },
            )
            engine.offer("hash1", manualLeg = row.leg, onComplete = { reports += it })
            advanceUntilIdle()

            assertEquals(row.name, listOf(false), reports)
            verify(coordinator, never()).attestOffchain(any(), any(), any(), any())
            verify(coordinator, never()).attestOnchain(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun manualJob_thrownJob_reportsFailedOnceAndProcessorSurvives() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        whenever(coordinator.attestOffchain(any(), any(), any(), any()))
            .thenReturn(Result.success(mock<LocationProtocolAttestationResult>()))
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        storage.seedProof("hash2")
        var storageCalls = 0
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = {
                if (storageCalls++ == 0) throw IllegalStateException("storage unavailable")
                storage
            },
        )
        engine.offer("hash1", manualLeg = LpManualLeg.OFFCHAIN, onComplete = { first += it })
        engine.offer("hash2", manualLeg = LpManualLeg.OFFCHAIN, onComplete = { second += it })
        advanceUntilIdle()

        assertEquals(listOf(false), first)
        assertEquals(listOf(true), second)
    }

    @Test
    fun enqueueManual_lpDisabled_reportsFailedAtOnce() {
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests(null)
        FeatureFlags.lpEnabled = false
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        val reports = mutableListOf<Boolean>()

        AutoCaptureLocationAttestationOrchestrator.enqueueManual(
            context,
            Uri.parse("content://test/1"),
            "hash1",
            LpManualLeg.OFFCHAIN,
            onComplete = { reports += it },
        )

        assertEquals(listOf(false), reports)
        assertEquals(0, AutoCaptureLocationAttestationOrchestrator.pendingCountForTests())
    }

    @Test
    fun autoCaptureJob_neverReports() = runTest {
        primeLpActiveEnabled()
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN
        val coordinator = mock<LocationProtocolAttestationCoordinator>()
        whenever(coordinator.attestOffchain(eq("ok"), any(), any(), any()))
            .thenReturn(Result.success(mock<LocationProtocolAttestationResult>()))
        whenever(coordinator.attestOffchain(eq("bad"), any(), any(), any()))
            .thenReturn(Result.failure(IllegalStateException("offchain failed")))
        val storage = OrchestratorTestStorage()
        storage.seedProof("ok")
        storage.seedProof("bad")
        storage.seedProof("skipped", latitude = 0.0, longitude = 0.0)
        val reports = mutableListOf<Boolean>()

        val engine = createEngine(
            coordinatorFactory = { _, _ -> coordinator },
            storageFactory = { storage },
        )
        engine.offer("ok", manualLeg = null, onComplete = { reports += it })
        engine.offer("bad", manualLeg = null, onComplete = { reports += it })
        engine.offer("skipped", manualLeg = null, onComplete = { reports += it })
        advanceUntilIdle()

        assertEquals(emptyList<Boolean>(), reports)
        verify(coordinator).attestOffchain(eq("ok"), any(), any(), any())
    }
}
