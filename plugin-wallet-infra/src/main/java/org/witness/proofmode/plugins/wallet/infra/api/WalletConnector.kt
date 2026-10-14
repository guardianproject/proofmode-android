package org.witness.proofmode.plugins.wallet.infra.api

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState

interface WalletConnector {
    val stateFlow: StateFlow<WalletState>

    suspend fun connect()

    suspend fun disconnect()

    fun getIdentity(): WalletIdentity?

    fun setChain(chainId: String)

    fun bindActivity(activity: Activity)

    /**
     * Release the Activity reference held by this connector.
     * Call in Activity.onStop() to prevent memory leaks from stale post-destroy references.
     * Must NOT cancel the process-scoped auth state observation.
     */
    fun unbindActivity()

    /**
     * Retry wallet creation after a [org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable]
     * state. Default is a no-op for connectors without a creatable wallet.
     */
    suspend fun retryWallet() {}

    /**
     * Ask the provider to verify a saved session that could not be verified yet
     * ([org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork]). Safe to call in
     * any state and while offline; never throws except for cancellation. Default is a no-op.
     */
    suspend fun refreshSession() {}
}
