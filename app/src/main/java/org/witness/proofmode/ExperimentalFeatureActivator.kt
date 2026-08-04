package org.witness.proofmode

import android.content.Context
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
import org.witness.proofmode.plugins.ipfscid.IpfsCidPlugin
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.util.ForegroundWalletActivityBinder
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object ExperimentalFeatureActivator {

    private val _activationState = MutableStateFlow<LpActivationState>(LpActivationState.Idle)
    val activationState: StateFlow<LpActivationState> = _activationState.asStateFlow()

    private val activateGuard = AtomicBoolean(false)
    private val sessionRestoreLaunchedThisProcess = AtomicBoolean(false)

    @VisibleForTesting
    internal val walletSessionRestoreLaunchCountForTests = AtomicInteger(0)

    @VisibleForTesting
    internal val lpEnabledAtActivationEntryForTests = AtomicReference<Boolean?>(null)

    internal fun resetActivationStateForTests(state: LpActivationState = LpActivationState.Idle) {
        _activationState.value = state
    }

    @VisibleForTesting
    internal fun resetActivationGuardsForTests() {
        activateGuard.set(false)
        sessionRestoreLaunchedThisProcess.set(false)
        walletSessionRestoreLaunchCountForTests.set(0)
        lpEnabledAtActivationEntryForTests.set(null)
    }

    /** Cold-start entry: activate whatever flags are already true in prefs. */
    fun bootstrapAtColdStart(app: ProofModeApp) {
        activateLocalIpfsCid(app.applicationContext)
        if (FeatureFlags.lpEnabled) {
            activateLocationProtocol(app)
        }
    }

    /** Hot path: CID toggle-ON or cold start with gate already true. */
    fun activateLocalIpfsCid(context: Context) {
        if (!FeatureFlags.localIpfsCidEnabled) return
        Timber.i("ExperimentalFeatureActivator: activating Local IPFS CID hooks")
        IpfsCidPlugin.register(context.applicationContext)
    }

    /** Hot path: LP toggle-ON or cold start with gate already true. */
    fun activateLocationProtocol(app: ProofModeApp) {
        if (!FeatureFlags.lpEnabled) return
        lpEnabledAtActivationEntryForTests.set(FeatureFlags.lpEnabled)
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
                if (alreadyRegistered) {
                    if (!LocationProtocolPlugin.isFlutterEngineReady()) {
                        withContext(Dispatchers.Main) {
                            LocationProtocolPlugin.initFlutterEngine(app)
                        }
                    }
                    ForegroundWalletActivityBinder.register(app)
                    installSkipListener(app)
                    maybeLaunchWalletSessionRestore(app)
                    emitReadyOrFailed()
                } else {
                    withContext(Dispatchers.IO) {
                        LocationProtocolPlugin.registerWalletStack(app)
                    }
                    withContext(Dispatchers.Main) {
                        LocationProtocolPlugin.initFlutterEngine(app)
                    }
                    ForegroundWalletActivityBinder.register(app)
                    installSkipListener(app)
                    maybeLaunchWalletSessionRestore(app)
                    emitReadyOrFailed()
                }
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
        walletSessionRestoreLaunchCountForTests.incrementAndGet()
        launchWalletSessionRestore(app)
    }

    private fun installSkipListener(app: ProofModeApp) {
        AutoCaptureLocationAttestationOrchestrator.installSkipListener { reason ->
            val messageRes = when (reason) {
                AutoCaptureSkipReason.LOCATION_UNAVAILABLE ->
                    R.string.lp_auto_capture_skip_location_unavailable
                AutoCaptureSkipReason.WALLET_UNAVAILABLE ->
                    R.string.lp_auto_capture_skip_wallet_unavailable
                AutoCaptureSkipReason.NO_FOREGROUND_ACTIVITY ->
                    R.string.lp_auto_capture_skip_no_activity
            }
            app.showToastMessage(app.getString(messageRes))
        }
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
