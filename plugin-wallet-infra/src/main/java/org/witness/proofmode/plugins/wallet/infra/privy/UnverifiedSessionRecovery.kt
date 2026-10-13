package org.witness.proofmode.plugins.wallet.infra.privy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import timber.log.Timber

/**
 * Recovers a saved session that Privy could not verify ([WalletAwaitingNetwork]).
 *
 * Privy 0.16.0 retries such a session by itself at most once: after an offline start it makes one
 * restore attempt when the network returns, and after a failed refresh at an online start it makes
 * none. Its documented recovery hook, `Privy.onNetworkRestored()` ([onNetworkRestored] here), does
 * nothing once the auth state is determined and signs the user out only on a 4xx answer from
 * Privy, so it is safe to call at any time, even offline.
 *
 * [state] is the connector's state; [networkAvailable] reports whether the device has a usable
 * network. Call [watch] once from a main-thread scope; [refresh] is also the manual trigger.
 */
internal class UnverifiedSessionRecovery(
    private val state: StateFlow<WalletState>,
    private val networkAvailable: () -> Flow<Boolean>,
    private val onNetworkRestored: suspend () -> Unit,
) {
    private companion object {
        const val TAG = "UnverifiedSessionRecovery"
    }

    /** Calls [onNetworkRestored] if the state is [WalletAwaitingNetwork]. Throws only cancellation. */
    suspend fun refresh() {
        if (state.value !is WalletAwaitingNetwork) return
        try {
            onNetworkRestored()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "refresh: Privy onNetworkRestored failed")
        }
    }

    /**
     * Runs until cancelled. While the state is [WalletAwaitingNetwork] it observes [networkAvailable]
     * and calls [refresh] each time the network becomes available, and once on entry if it already
     * is. It stops observing the network as soon as the state changes. A running refresh is not
     * cancelled by that state change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun watch() {
        state.map { it is WalletAwaitingNetwork }
            .distinctUntilChanged()
            .flatMapLatest { awaiting ->
                if (awaiting) networkAvailable().distinctUntilChanged().filter { it } else emptyFlow()
            }
            .collect { refresh() }
    }
}
