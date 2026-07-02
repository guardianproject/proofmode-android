package org.witness.proofmode

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.witness.proofmode.lp.AutoCaptureLocationAttestationOrchestrator
import org.witness.proofmode.lp.AutoCaptureSkipReason
import org.witness.proofmode.plugins.ipfscid.IpfsCidPlugin
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.util.ForegroundWalletActivityBinder
import timber.log.Timber

object ExperimentalFeatureActivator {

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
        if (!LocationProtocolPlugin.isWalletStackRegistered()) {
            Timber.i("ExperimentalFeatureActivator: registering Location Protocol plugin")
            LocationProtocolPlugin.register(app)
        }
        ForegroundWalletActivityBinder.register(app)
        installSkipListener(app)
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
