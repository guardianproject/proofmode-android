package org.witness.proofmode.lp

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationCoordinator
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolCoordinateValidator
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.autocapture.LpBadgePhase
import org.witness.proofmode.plugins.lp.autocapture.LpRunState
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.util.ProofModeUtil
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

internal data class OrchestratorTestGuards(
    val walletConnected: Boolean = true,
    val walletActivityBoundProvider: () -> Boolean = { true },
)

object AutoCaptureLocationAttestationOrchestrator {

    internal const val PROOF_EXISTS_POLL_MS = 500L
    internal const val PROOF_EXISTS_TIMEOUT_MS = 30_000L
    internal const val WALLET_ACTIVITY_WAIT_TIMEOUT_MS = 30_000L
    internal const val WALLET_ACTIVITY_POLL_MS = 100L

    @Volatile
    private var skipListener: AutoCaptureSkipListener? = null

    @Volatile
    private var enqueueInterceptor: ((Context, Uri, String) -> Unit)? = null

    @Volatile
    private var defaultEngine: OrchestratorEngine? = null

    fun installSkipListener(listener: AutoCaptureSkipListener?) {
        skipListener = listener
    }

    @VisibleForTesting
    internal fun setEnqueueInterceptorForTests(interceptor: ((Context, Uri, String) -> Unit)?) {
        enqueueInterceptor = interceptor
    }

    fun enqueue(context: Context, mediaUri: Uri, mediaHash: String) {
        enqueueInterceptor?.invoke(context, mediaUri, mediaHash)
            ?: run {
                if (!FeatureFlags.lpActive || !FeatureFlags.autoCaptureLpMode.isActive) {
                    return
                }
                ensureDefaultEngine(context.applicationContext).offer(context, mediaUri, mediaHash)
            }
    }

    fun enqueueManual(context: Context, mediaUri: Uri, mediaHash: String, leg: LpManualLeg) {
        enqueueInterceptor?.invoke(context, mediaUri, mediaHash)
            ?: run {
                if (!FeatureFlags.lpActive) {
                    return
                }
                ensureDefaultEngine(context.applicationContext)
                    .offerManual(mediaUri, mediaHash, leg)
            }
    }

    @VisibleForTesting
    internal fun pendingCountForTests(): Int =
        defaultEngine?.pendingCount() ?: 0

    @VisibleForTesting
    internal fun createForTests(
        context: Context,
        coordinatorFactory: (StorageProvider, Context) -> LocationProtocolAttestationCoordinator,
        proofExistsDelayMs: Map<String, Long> = emptyMap(),
        storageFactory: ((Context) -> StorageProvider)? = null,
        testGuards: OrchestratorTestGuards = OrchestratorTestGuards(),
        walletActivityWaitTimeoutMs: Long = WALLET_ACTIVITY_WAIT_TIMEOUT_MS,
        autoStart: Boolean = true,
        scope: CoroutineScope? = null,
        processorDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): TestOrchestratorHandle {
        val testScope = scope ?: CoroutineScope(SupervisorJob() + processorDispatcher)
        val engine = OrchestratorEngine(
            appContext = context.applicationContext,
            scope = testScope,
            coordinatorFactory = coordinatorFactory,
            proofExistsDelayMs = proofExistsDelayMs,
            storageFactory = storageFactory,
            testGuards = testGuards,
            walletActivityWaitTimeoutMs = walletActivityWaitTimeoutMs,
            processorDispatcher = processorDispatcher,
            ioDispatcher = ioDispatcher,
            skipListenerProvider = { skipListener },
        )
        if (autoStart) {
            engine.startProcessor()
        }
        return TestOrchestratorHandle(engine)
    }

    @VisibleForTesting
    internal fun resetForTests() {
        defaultEngine?.shutdown()
        defaultEngine = null
        enqueueInterceptor = null
    }

    private fun ensureDefaultEngine(appContext: Context): OrchestratorEngine {
        return defaultEngine ?: synchronized(this) {
            defaultEngine ?: OrchestratorEngine(
                appContext = appContext,
                scope = LocationProtocolPlugin.requireApplicationScope(),
                coordinatorFactory = { storage, ctx ->
                    LocationProtocolPlugin.createCoordinator(storage, ctx)
                },
                skipListenerProvider = { skipListener },
            ).also { engine ->
                engine.startProcessor()
                defaultEngine = engine
            }
        }
    }

    @VisibleForTesting
    internal class TestOrchestratorHandle internal constructor(
        private val engine: OrchestratorEngine,
    ) {
        fun enqueueTestJob(
            mediaHash: String,
            mediaUri: Uri = Uri.parse("content://test/$mediaHash"),
            manualLeg: LpManualLeg? = null,
        ) {
            engine.offerDirect(
                mediaUri = mediaUri,
                mediaHash = mediaHash,
                manualLeg = manualLeg,
            )
        }

        fun offerManualForTests(
            mediaHash: String,
            leg: LpManualLeg,
            mediaUri: Uri = Uri.parse("content://test/$mediaHash"),
        ) {
            engine.offerManual(mediaUri = mediaUri, mediaHash = mediaHash, leg = leg)
        }

        fun offerForTests(
            context: Context,
            mediaHash: String,
            mediaUri: Uri = Uri.parse("content://test/$mediaHash"),
        ) {
            engine.offer(context, mediaUri, mediaHash)
        }

        fun pendingCountForTests(): Int = engine.pendingCount()

        fun startProcessorForTests() {
            engine.startProcessor()
        }

        fun shutdownForTests() {
            engine.shutdown()
        }
    }
}

internal class OrchestratorEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val coordinatorFactory: (StorageProvider, Context) -> LocationProtocolAttestationCoordinator,
    private val proofExistsDelayMs: Map<String, Long> = emptyMap(),
    private val storageFactory: ((Context) -> StorageProvider)? = null,
    private val testGuards: OrchestratorTestGuards = OrchestratorTestGuards(),
    private val walletActivityWaitTimeoutMs: Long =
        AutoCaptureLocationAttestationOrchestrator.WALLET_ACTIVITY_WAIT_TIMEOUT_MS,
    private val processorDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val skipListenerProvider: () -> AutoCaptureSkipListener?,
) {

    private val queue = Channel<AutoCaptureJob>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private val processMutex = Mutex()
    private var processorJob: Job? = null

    fun startProcessor() {
        if (processorJob != null) return
        processorJob = scope.launch(processorDispatcher) {
            for (job in queue) {
                processMutex.withLock {
                    pending.decrementAndGet()
                    try {
                        processJob(appContext, job)
                    } catch (e: Exception) {
                        Timber.e(e, "AutoCapture: job failed hash=%s", job.mediaHash)
                    }
                }
            }
        }
    }

    fun offer(context: Context, mediaUri: Uri, mediaHash: String) {
        if (!FeatureFlags.lpActive || !FeatureFlags.autoCaptureLpMode.isActive) {
            return
        }
        offerDirect(mediaUri, mediaHash)
    }

    fun offerDirect(
        mediaUri: Uri,
        mediaHash: String,
        manualLeg: LpManualLeg? = null,
    ) {
        pending.incrementAndGet()
        queue.trySend(AutoCaptureJob(mediaHash = mediaHash, mediaUri = mediaUri, manualLeg = manualLeg))
    }

    fun offerManual(mediaUri: Uri, mediaHash: String, leg: LpManualLeg) {
        offerDirect(mediaUri, mediaHash, manualLeg = leg)
    }

    fun pendingCount(): Int = pending.get()

    fun shutdown() {
        processorJob?.cancel()
        processorJob = null
        queue.close()
    }

    private suspend fun processJob(appContext: Context, job: AutoCaptureJob) {
        if (!FeatureFlags.lpActive) {
            return
        }

        val storage = storageFactory?.invoke(appContext) ?: DefaultStorageProvider(appContext)
        if (!waitForProofSidecar(storage, job.mediaHash)) {
            when (val leg = job.manualLeg) {
                null -> {
                    AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.OFFCHAIN, LpRunState.FAILED)
                    AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.FAILED)
                }
                else -> failManualLeg(job.mediaHash, leg)
            }
            Timber.w("AutoCapture: proof sidecar timeout for hash=%s", job.mediaHash)
            return
        }

        when (job.manualLeg) {
            null -> processAutoCaptureJob(appContext, job, storage)
            LpManualLeg.OFFCHAIN -> processManualOffchainJob(appContext, job, storage)
            LpManualLeg.ONCHAIN -> processManualOnchainJob(appContext, job, storage)
        }
    }

    private suspend fun processAutoCaptureJob(
        appContext: Context,
        job: AutoCaptureJob,
        storage: StorageProvider,
    ) {
        val mode = FeatureFlags.autoCaptureLpMode
        if (mode == AutoCaptureLpMode.OFF) {
            return
        }

        if (!isWalletConnected()) {
            markBothLegsSkipped(job.mediaHash, mode)
            notifySkip(AutoCaptureSkipReason.WALLET_UNAVAILABLE)
            return
        }

        if (!isLocationAvailable(storage, job.mediaHash)) {
            markBothLegsSkipped(job.mediaHash, mode)
            notifySkip(AutoCaptureSkipReason.LOCATION_UNAVAILABLE)
            return
        }

        val coordinator = coordinatorFactory(storage, appContext)
        val artifactStore = LocationProtocolArtifactStore(storage)

        if (mode == AutoCaptureLpMode.OFFCHAIN || mode == AutoCaptureLpMode.BOTH) {
            runOffchainLeg(appContext, job, storage, coordinator, artifactStore)
        }

        if (mode == AutoCaptureLpMode.ONCHAIN || mode == AutoCaptureLpMode.BOTH) {
            runOnchainLeg(appContext, job, storage, coordinator, artifactStore)
        }
    }

    private suspend fun processManualOffchainJob(
        appContext: Context,
        job: AutoCaptureJob,
        storage: StorageProvider,
    ) {
        if (!isWalletConnected()) {
            skipManualLeg(job.mediaHash, LpManualLeg.OFFCHAIN)
            notifySkip(AutoCaptureSkipReason.WALLET_UNAVAILABLE)
            return
        }
        if (!isLocationAvailable(storage, job.mediaHash)) {
            skipManualLeg(job.mediaHash, LpManualLeg.OFFCHAIN)
            notifySkip(AutoCaptureSkipReason.LOCATION_UNAVAILABLE)
            return
        }
        val coordinator = coordinatorFactory(storage, appContext)
        val artifactStore = LocationProtocolArtifactStore(storage)
        runOffchainLeg(appContext, job, storage, coordinator, artifactStore)
    }

    private suspend fun processManualOnchainJob(
        appContext: Context,
        job: AutoCaptureJob,
        storage: StorageProvider,
    ) {
        if (!isWalletConnected()) {
            skipManualLeg(job.mediaHash, LpManualLeg.ONCHAIN)
            notifySkip(AutoCaptureSkipReason.WALLET_UNAVAILABLE)
            return
        }
        if (!isLocationAvailable(storage, job.mediaHash)) {
            skipManualLeg(job.mediaHash, LpManualLeg.ONCHAIN)
            notifySkip(AutoCaptureSkipReason.LOCATION_UNAVAILABLE)
            return
        }
        val coordinator = coordinatorFactory(storage, appContext)
        val artifactStore = LocationProtocolArtifactStore(storage)
        runOnchainLeg(appContext, job, storage, coordinator, artifactStore)
    }

    private fun manualBadgePhase(leg: LpManualLeg): LpBadgePhase = when (leg) {
        LpManualLeg.OFFCHAIN -> LpBadgePhase.OFFCHAIN
        LpManualLeg.ONCHAIN -> LpBadgePhase.ONCHAIN
    }

    private fun updateManualLegState(mediaHash: String, leg: LpManualLeg, state: LpRunState) {
        AutoCaptureLpStateRegistry.updateLeg(mediaHash, manualBadgePhase(leg), state)
    }

    private fun failManualLeg(mediaHash: String, leg: LpManualLeg) {
        updateManualLegState(mediaHash, leg, LpRunState.FAILED)
    }

    private fun skipManualLeg(mediaHash: String, leg: LpManualLeg) {
        updateManualLegState(mediaHash, leg, LpRunState.SKIPPED)
    }

    private suspend fun runOffchainLeg(
        appContext: Context,
        job: AutoCaptureJob,
        storage: StorageProvider,
        coordinator: LocationProtocolAttestationCoordinator,
        artifactStore: LocationProtocolArtifactStore,
    ) {
        val offchainId = "${job.mediaHash}${LocationProtocolArtifactStore.OFFCHAIN_SUFFIX}"
        val legacyId = "${job.mediaHash}${LocationProtocolArtifactStore.LEGACY_SUFFIX}"
        if (storage.proofIdentifierExists(job.mediaHash, offchainId) ||
            storage.proofIdentifierExists(job.mediaHash, legacyId)
        ) {
            AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.OFFCHAIN, LpRunState.SKIPPED)
            return
        }

        AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.OFFCHAIN, LpRunState.RUNNING)
        val result = coordinator.attestOffchain(job.mediaHash, job.mediaUri, appContext)
        AutoCaptureLpStateRegistry.updateLeg(
            job.mediaHash,
            LpBadgePhase.OFFCHAIN,
            if (result.isSuccess) LpRunState.SUCCEEDED else LpRunState.FAILED,
        )
    }

    private suspend fun runOnchainLeg(
        appContext: Context,
        job: AutoCaptureJob,
        storage: StorageProvider,
        coordinator: LocationProtocolAttestationCoordinator,
        artifactStore: LocationProtocolArtifactStore,
    ) {
        val onchainId = "${job.mediaHash}${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}"
        val onchainPendingId = "${job.mediaHash}${LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX}"
        if (storage.proofIdentifierExists(job.mediaHash, onchainId)) {
            AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SKIPPED)
            return
        }
        if (storage.proofIdentifierExists(job.mediaHash, onchainPendingId)) {
            AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SKIPPED)
            return
        }

        if (!isWalletActivityBound()) {
            val shouldDefer = job.manualLeg == LpManualLeg.ONCHAIN
            if (shouldDefer) {
                val bound = if (storageFactory != null) {
                    waitForWalletActivityBoundTestable()
                } else {
                    org.witness.proofmode.util.ForegroundWalletActivityBinder
                        .awaitWalletActivityBound(walletActivityWaitTimeoutMs)
                }
                if (!bound) {
                    AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SKIPPED)
                    notifySkip(AutoCaptureSkipReason.NO_FOREGROUND_ACTIVITY)
                    return
                }
            } else {
                AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SKIPPED)
                notifySkip(AutoCaptureSkipReason.NO_FOREGROUND_ACTIVITY)
                return
            }
        }

        AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.RUNNING)
        val result = coordinator.attestOnchain(
            mediaHash = job.mediaHash,
            mediaUri = job.mediaUri,
            context = appContext,
            onchainConfirmed = { AutoCaptureLpStateRegistry.notifyArtifactUpdated(it) },
        )
        when {
            result.isFailure -> {
                AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.FAILED)
            }
            storage.proofIdentifierExists(job.mediaHash, onchainPendingId) -> {
                // Pending artifact: keep RUNNING until confirmation callback refreshes badges.
            }
            storage.proofIdentifierExists(job.mediaHash, onchainId) -> {
                AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SUCCEEDED)
            }
            else -> {
                AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SUCCEEDED)
            }
        }
    }

    private suspend fun waitForWalletActivityBoundTestable(): Boolean {
        if (isWalletActivityBound()) return true
        val pollMs = AutoCaptureLocationAttestationOrchestrator.WALLET_ACTIVITY_POLL_MS
        val maxPolls = ((walletActivityWaitTimeoutMs + pollMs - 1) / pollMs).coerceAtLeast(1)
        repeat(maxPolls.toInt()) {
            delay(pollMs)
            if (isWalletActivityBound()) return true
        }
        return isWalletActivityBound()
    }

    private suspend fun waitForProofSidecar(storage: StorageProvider, mediaHash: String): Boolean {
        val delayMs = proofExistsDelayMs[mediaHash] ?: 0L
        if (delayMs > 0) {
            delay(delayMs)
        }
        val deadline = System.currentTimeMillis() + AutoCaptureLocationAttestationOrchestrator.PROOF_EXISTS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val exists = withContext(ioDispatcher) { storage.proofExists(mediaHash) }
            if (exists) {
                return true
            }
            delay(AutoCaptureLocationAttestationOrchestrator.PROOF_EXISTS_POLL_MS)
        }
        return false
    }

    private fun isLocationAvailable(storage: StorageProvider, mediaHash: String): Boolean {
        val map = ProofModeUtil.getProofHashMap(storage, mediaHash)
        val lat = map[ProofModeV1Constants.LOCATION_LATITUDE]?.trim()?.toDoubleOrNull()
        val lng = map[ProofModeV1Constants.LOCATION_LONGITUDE]?.trim()?.toDoubleOrNull()
        return LocationProtocolCoordinateValidator.isValid(lat, lng)
    }

    private fun markBothLegsSkipped(mediaHash: String, mode: AutoCaptureLpMode) {
        if (mode == AutoCaptureLpMode.OFFCHAIN || mode == AutoCaptureLpMode.BOTH) {
            AutoCaptureLpStateRegistry.updateLeg(mediaHash, LpBadgePhase.OFFCHAIN, LpRunState.SKIPPED)
        }
        if (mode == AutoCaptureLpMode.ONCHAIN || mode == AutoCaptureLpMode.BOTH) {
            AutoCaptureLpStateRegistry.updateLeg(mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SKIPPED)
        }
    }

    private fun isWalletConnected(): Boolean =
        if (storageFactory != null) {
            testGuards.walletConnected
        } else {
            LocationProtocolPlugin.walletDiagnostics().connected
        }

    private fun isWalletActivityBound(): Boolean =
        if (storageFactory != null) {
            testGuards.walletActivityBoundProvider()
        } else {
            LocationProtocolPlugin.hasWalletActivityBound()
        }

    private fun notifySkip(reason: AutoCaptureSkipReason) {
        skipListenerProvider()?.onSkip(reason)
    }
}
