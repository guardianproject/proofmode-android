package org.witness.proofmode

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.lp.AutoCaptureLocationAttestationOrchestrator
import org.witness.proofmode.lp.AutoCaptureSkipReason
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.ui.WalletRequiredDialogActivity
import org.witness.proofmode.util.ForegroundWalletActivityBinder
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

object ExperimentalFeatureActivator {

    private val _activationState = MutableStateFlow<LpActivationState>(LpActivationState.Idle)
    val activationState: StateFlow<LpActivationState> = _activationState.asStateFlow()

    private val activateGuard = AtomicBoolean(false)
    private val sessionRestoreLaunchedThisProcess = AtomicBoolean(false)

    internal fun resetActivationStateForTests(state: LpActivationState = LpActivationState.Idle) {
        _activationState.value = state
    }

    @VisibleForTesting
    internal fun resetActivationGuardsForTests() {
        activateGuard.set(false)
        sessionRestoreLaunchedThisProcess.set(false)
    }

    /** Cold-start entry: activate whatever flags are already true in prefs. */
    fun bootstrapAtColdStart(app: ProofModeApp) {
        if (FeatureFlags.lpEnabled) {
            activateLocationProtocol(app)
        }
    }

    /** Hot path: LP toggle-ON or cold start with gate already true. */
    fun activateLocationProtocol(app: ProofModeApp) {
        if (!FeatureFlags.lpEnabled) return
        if (!activateGuard.compareAndSet(false, true)) return

        val alreadyRegistered = LocationProtocolPlugin.isWalletStackRegistered()
        val engineReady = LocationProtocolPlugin.isFlutterEngineReady()

        if (alreadyRegistered && engineReady) {
            ForegroundWalletActivityBinder.register(app)
            installSkipListener(app)
        }

        LocationProtocolPlugin.requireApplicationScope().launch {
            try {
                _activationState.value = LpActivationState.Activating
                if (!alreadyRegistered) {
                    withContext(Dispatchers.IO) {
                        LocationProtocolPlugin.registerWalletStack(app)
                    }
                }
                if (!LocationProtocolPlugin.isFlutterEngineReady()) {
                    withContext(Dispatchers.Main) {
                        LocationProtocolPlugin.initFlutterEngine(app)
                    }
                }
                ForegroundWalletActivityBinder.register(app)
                installSkipListener(app)
                maybeLaunchWalletSessionRestore(app)
                emitReadyOrFailed()
            } catch (t: Throwable) {
                Timber.w(t, "LP activation failed")
                _activationState.value = LpActivationState.Failed
            } finally {
                activateGuard.set(false)
            }
        }
    }

    private fun emitReadyOrFailed() {
        val ready = LocationProtocolPlugin.isWalletStackRegistered() &&
            LocationProtocolPlugin.isFlutterEngineReady()
        _activationState.value = if (ready) LpActivationState.Ready else LpActivationState.Failed
    }

    private fun maybeLaunchWalletSessionRestore(app: ProofModeApp) {
        if (!sessionRestoreLaunchedThisProcess.compareAndSet(false, true)) return
        launchWalletSessionRestore(app)
    }

    private fun installSkipListener(app: ProofModeApp) {
        AutoCaptureLocationAttestationOrchestrator.installSkipListener { reason ->
            // The orchestrator calls back off the main thread; the foreground read and the launch need Main.
            Handler(Looper.getMainLooper()).post { handleSkip(app, reason) }
        }
    }

    /**
     * The wallet skip is shown only as the dialog, and only while the app is on screen: Android blocks an
     * activity start from the background, so there it is dropped rather than toasted.
     *
     * Not `ProcessLifecycleOwner`: this app removes the androidx.startup provider that attaches it, so it
     * reads INITIALIZED for the whole process and the dialog would never show. The bound wallet activity
     * stands in for "app foreground" (an activity is started). It reads empty once the floating dialog
     * closes, because the camera beneath never restarts; that is acceptable since the person then connects
     * a wallet or turns auto-attest off.
     */
    @VisibleForTesting
    internal fun handleSkip(app: ProofModeApp, reason: AutoCaptureSkipReason) {
        val messageRes = when (reason) {
            AutoCaptureSkipReason.AUTO_CAPTURE_WALLET_UNAVAILABLE -> {
                if (LocationProtocolPlugin.hasWalletActivityBound()) WalletRequiredDialogActivity.launch(app)
                return
            }
            AutoCaptureSkipReason.LOCATION_UNAVAILABLE ->
                R.string.lp_auto_capture_skip_location_unavailable
            AutoCaptureSkipReason.NO_FOREGROUND_ACTIVITY ->
                R.string.lp_auto_capture_skip_no_activity
        }
        app.showToastMessage(app.getString(messageRes))
    }

    private fun launchWalletSessionRestore(app: ProofModeApp) {
        LocationProtocolPlugin.requireApplicationScope().launch(Dispatchers.IO) {
            try {
                LocationProtocolPlugin.restoreWalletSession(
                    appContext = app,
                    scope = ProcessLifecycleOwner.get().lifecycleScope,
                )
                Timber.i("ExperimentalFeatureActivator: wallet session restore completed")
            } catch (e: Throwable) {
                Timber.w(e, "ExperimentalFeatureActivator: wallet session restore failed (non-fatal)")
            }
        }
    }
}
