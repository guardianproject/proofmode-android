package org.witness.proofmode.plugins.lp.wallet

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState

class FakeWalletConnector(initial: WalletState) : WalletConnector {
    override val stateFlow: StateFlow<WalletState> = MutableStateFlow(initial)
    override suspend fun connect() {}
    override suspend fun disconnect() {}
    override fun getIdentity(): WalletIdentity? = null
    override fun setChain(chainId: String) {}
    override fun bindActivity(activity: Activity) {}
    override fun unbindActivity() {}
}
