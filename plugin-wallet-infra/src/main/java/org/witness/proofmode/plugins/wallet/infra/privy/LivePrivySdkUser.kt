package org.witness.proofmode.plugins.wallet.infra.privy

import io.privy.auth.LinkedAccount
import io.privy.auth.PrivyUser

/** [PrivySdkUser] backed by a real Privy SDK user. */
internal class LivePrivySdkUser(private val user: PrivyUser) : PrivySdkUser {

    /** Non-blank addresses of the user's embedded Ethereum wallets, in linked-account order. */
    override val walletAddresses: List<String>
        get() = user.linkedAccounts
            .asSequence()
            .filterIsInstance<LinkedAccount.EmbeddedEthereumWalletAccount>()
            .map { it.address }
            .filter { it.isNotBlank() }
            .toList()

    override suspend fun createEthereumWallet(): String =
        user.createEthereumWallet(allowAdditional = false).getOrThrow().address

    override suspend fun refresh() {
        user.refresh().getOrThrow()
    }
}
