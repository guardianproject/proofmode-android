package org.witness.proofmode.lp

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.plugins.lp.attestation.PendingOnchainRecheckOutcome
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationCoordinator
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolCoordinateValidator
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.autocapture.LpBadgePhase
import org.witness.proofmode.plugins.lp.autocapture.LpRunState
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.util.ForegroundWalletActivityBinder
import org.witness.proofmode.util.ProofModeUtil
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

object AutoCaptureLocationAttestationOrchestrator {

    internal const val PROOF_EXISTS_POLL_MS = 500L
    internal const val PROOF_EXISTS_TIMEOUT_MS = 30_000L
    internal const val WALLET_ACTIVITY_WAIT_TIMEOUT_MS = 30_000L

    @Volatile
    private var skipListener: AutoCaptureSkipListener? = null

    @Volatile
    private var enqueueInterceptor: ((Context, Uri, String, ((Boolean) -> Unit)?) -> Unit)? = null

    @Volatile
    private var defaultEngine: OrchestratorEngine? = null

    fun installSkipListener(listener: AutoCaptureSkipListener?) {
        skipListener = listener
    }

    @VisibleForTesting
    internal fun setEnqueueInterceptorForTests(
        interceptor: ((Context, Uri, String, ((Boolean) -> Unit)?) -> Unit)?,
    ) {
        enqueueInterceptor = interceptor
    }

    fun enqueue(context: Context, mediaUri: Uri, mediaHash: String) {
        enqueueInterceptor?.invoke(context, mediaUri, mediaHash, null)
            ?: run {
                if (!FeatureFlags.lpActive || !FeatureFlags.autoCaptureLpMode.isActive) {
                    return
                }
                ensureDefaultEngine(context.applicationContext).offerDirect(mediaUri, mediaHash)
            }
    }

    /** [onComplete] is called exactly once per call, true when the leg's artifact is in place. */
    fun enqueueManual(
        context: Context,
        mediaUri: Uri,
        mediaHash: String,
        leg: LpManualLeg,
        onComplete: (Boolean) -> Unit,
    ) {
        enqueueInterceptor?.invoke(context, mediaUri, mediaHash, onComplete)
            ?: run {
                if (!FeatureFlags.lpActive) {
                    onComplete(false)
                    return
                }
                ensureDefaultEngine(context.applicationContext)
                    .offerDirect(mediaUri, mediaHash, leg, onComplete)
            }
    }

    @VisibleForTesting
    internal fun pendingCountForTests(): Int =
        defaultEngine?.pendingCount() ?: 0

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

}

internal class OrchestratorEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val coordinatorFactory: (StorageProvider, Context) -> LocationProtocolAttestationCoordinator,
    private val storageFactory: ((Context) -> StorageProvider)? = null,
    private val isWalletConnected: () -> Boolean = { LocationProtocolPlugin.walletDiagnostics().connected },
    private val awaitWalletActivityBound: suspend () -> Boolean = {
        ForegroundWalletActivityBinder.awaitWalletActivityBound(
            AutoCaptureLocationAttestationOrchestrator.WALLET_ACTIVITY_WAIT_TIMEOUT_MS,
        )
    },
    private val processorDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val skipListenerProvider: () -> AutoCaptureSkipListener?,
) {

    private val queue = Channel<AutoCaptureJob>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private var processorJob: Job? = null

    fun startProcessor() {
        if (processorJob != null) return
        processorJob = scope.launch(processorDispatcher) {
            for (job in queue) {
                pending.decrementAndGet()
                try {
                    processJob(appContext, job)
                } catch (e: Exception) {
                    Timber.e(e, "AutoCapture: job failed hash=%s", job.mediaHash)
                }
            }
        }
    }

    fun offerDirect(
        mediaUri: Uri,
        mediaHash: String,
        manualLeg: LpManualLeg? = null,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        pending.incrementAndGet()
        queue.trySend(
            AutoCaptureJob(
                mediaHash = mediaHash,
                mediaUri = mediaUri,
                manualLeg = manualLeg,
                onComplete = onComplete,
            ),
        )
    }

    fun pendingCount(): Int = pending.get()

    fun shutdown() {
        processorJob?.cancel()
        processorJob = null
        queue.close()
    }

    private suspend fun processJob(appContext: Context, job: AutoCaptureJob) {
        var ok = false
        try {
            if (!FeatureFlags.lpActive) {
                return
            }

            val storage = storageFactory?.invoke(appContext) ?: DefaultStorageProvider(appContext)
            if (!waitForProofSidecar(storage, job.mediaHash)) {
                Timber.w("AutoCapture: proof sidecar timeout for hash=%s", job.mediaHash)
                return
            }

            val leg = job.manualLeg
            if (leg == null) {
                processAutoCaptureJob(appContext, job, storage)
            } else {
                ok = processManualJob(appContext, job, storage, leg)
            }
        } finally {
            // Every exit, including a throw the processor loop swallows, must reach the tap that
            // is counting on this job; a missed report would leave its toast waiting forever.
            if (job.manualLeg != null) {
                job.onComplete?.invoke(ok)
            }
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
            notifySkip(AutoCaptureSkipReason.AUTO_CAPTURE_WALLET_UNAVAILABLE)
            return
        }

        if (!isLocationAvailable(storage, job.mediaHash)) {
            notifySkip(AutoCaptureSkipReason.LOCATION_UNAVAILABLE)
            return
        }

        val coordinator = coordinatorFactory(storage, appContext)

        if (mode == AutoCaptureLpMode.OFFCHAIN || mode == AutoCaptureLpMode.BOTH) {
            runOffchainLeg(appContext, job, storage, coordinator)
        }

        if (mode == AutoCaptureLpMode.ONCHAIN || mode == AutoCaptureLpMode.BOTH) {
            runOnchainLeg(appContext, job, storage, coordinator)
        }
    }

    private suspend fun processManualJob(
        appContext: Context,
        job: AutoCaptureJob,
        storage: StorageProvider,
        leg: LpManualLeg,
    ): Boolean {
        // Silent on purpose: the tap site asks for a wallet before queueing, so only a disconnect between
        // the tap and this job gets here.
        if (!isWalletConnected()) return false
        if (!isLocationAvailable(storage, job.mediaHash)) {
            notifySkip(AutoCaptureSkipReason.LOCATION_UNAVAILABLE)
            return false
        }
        val coordinator = coordinatorFactory(storage, appContext)
        return when (leg) {
            LpManualLeg.OFFCHAIN -> runOffchainLeg(appContext, job, storage, coordinator)
            LpManualLeg.ONCHAIN -> runOnchainLeg(appContext, job, storage, coordinator)
        }
    }

    private suspend fun runOffchainLeg(
        appContext: Context,
        job: AutoCaptureJob,
        storage: StorageProvider,
        coordinator: LocationProtocolAttestationCoordinator,
    ): Boolean {
        if (hasArtifactForManualLeg(storage, job.mediaHash, LpManualLeg.OFFCHAIN)) {
            AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.OFFCHAIN, LpRunState.SKIPPED)
            return true
        }

        AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.OFFCHAIN, LpRunState.RUNNING)
        val result = coordinator.attestOffchain(job.mediaHash, job.mediaUri, appContext)
        AutoCaptureLpStateRegistry.updateLeg(
            job.mediaHash,
            LpBadgePhase.OFFCHAIN,
            if (result.isSuccess) LpRunState.SUCCEEDED else LpRunState.FAILED,
        )
        return result.isSuccess
    }

    private suspend fun runOnchainLeg(
        appContext: Context,
        job: AutoCaptureJob,
        storage: StorageProvider,
        coordinator: LocationProtocolAttestationCoordinator,
    ): Boolean {
        val onchainId = "${job.mediaHash}${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}"
        val onchainPendingId = "${job.mediaHash}${LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX}"
        if (storage.proofIdentifierExists(job.mediaHash, onchainId)) {
            AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SKIPPED)
            return true
        }
        // A pending record is re-checked rather than trusted, so a lost confirmation can still
        // finish, and a transaction that never landed is resubmitted instead of blocking forever.
        if (storage.proofIdentifierExists(job.mediaHash, onchainPendingId)) {
            when (coordinator.recheckPendingOnchain(job.mediaHash)) {
                is PendingOnchainRecheckOutcome.Confirmed -> {
                    AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SUCCEEDED)
                    return true
                }
                PendingOnchainRecheckOutcome.StillPending -> {
                    AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SKIPPED)
                    return true
                }
                PendingOnchainRecheckOutcome.Resubmit -> Unit
            }
        }

        // Only a manual tap waits for the wallet activity; an auto-capture job skips at once.
        val bound = if (job.manualLeg == LpManualLeg.ONCHAIN) {
            awaitWalletActivityBound()
        } else {
            LocationProtocolPlugin.hasWalletActivityBound()
        }
        if (!bound) {
            notifySkip(AutoCaptureSkipReason.NO_FOREGROUND_ACTIVITY)
            return false
        }

        AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.RUNNING)
        val result = coordinator.attestOnchain(
            mediaHash = job.mediaHash,
            mediaUri = job.mediaUri,
            context = appContext,
            onchainConfirmed = { hash ->
                AutoCaptureLpStateRegistry.updateLeg(hash, LpBadgePhase.ONCHAIN, LpRunState.SUCCEEDED)
            },
            onchainFailed = { hash ->
                AutoCaptureLpStateRegistry.updateLeg(hash, LpBadgePhase.ONCHAIN, LpRunState.FAILED)
            },
        )
        when {
            result.isFailure -> {
                AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.FAILED)
            }
            storage.proofIdentifierExists(job.mediaHash, onchainId) -> {
                AutoCaptureLpStateRegistry.updateLeg(job.mediaHash, LpBadgePhase.ONCHAIN, LpRunState.SUCCEEDED)
            }
            // Otherwise the confirmation callbacks own the terminal state. They can fire before this
            // check (a revert deletes the pending record), so no file here is not a success.
        }
        // Success means submitted: attestOnchain succeeds once the pending artifact is saved, and a
        // later confirmation failure is deliberately not surfaced through this report.
        return result.isSuccess
    }

    private suspend fun waitForProofSidecar(storage: StorageProvider, mediaHash: String): Boolean {
        // Bound by poll count, not a wall-clock deadline: under runTest `delay` is virtual, so a
        // real-clock deadline would spin for the full timeout and flood the mock with calls.
        val pollMs = AutoCaptureLocationAttestationOrchestrator.PROOF_EXISTS_POLL_MS
        val timeoutMs = AutoCaptureLocationAttestationOrchestrator.PROOF_EXISTS_TIMEOUT_MS
        val maxPolls = ((timeoutMs + pollMs - 1) / pollMs).coerceAtLeast(1)
        repeat(maxPolls.toInt()) {
            val exists = withContext(ioDispatcher) { storage.proofExists(mediaHash) }
            if (exists) {
                return true
            }
            delay(pollMs)
        }
        return false
    }

    private fun isLocationAvailable(storage: StorageProvider, mediaHash: String): Boolean {
        val map = ProofModeUtil.getProofHashMap(storage, mediaHash)
        val lat = map[ProofModeV1Constants.LOCATION_LATITUDE]?.trim()?.toDoubleOrNull()
        val lng = map[ProofModeV1Constants.LOCATION_LONGITUDE]?.trim()?.toDoubleOrNull()
        return LocationProtocolCoordinateValidator.isValid(lat, lng)
    }

    private fun notifySkip(reason: AutoCaptureSkipReason) {
        skipListenerProvider()?.onSkip(reason)
    }
}
