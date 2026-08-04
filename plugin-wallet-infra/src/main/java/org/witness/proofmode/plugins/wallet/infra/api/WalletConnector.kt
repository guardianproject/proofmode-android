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
}
