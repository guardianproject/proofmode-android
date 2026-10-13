package org.witness.proofmode.plugins.wallet.infra.privy

/**
 * The parts of a Privy user that embedded-wallet creation needs. It keeps
 * [EmbeddedWalletCoordinator] free of the Privy SDK so it can be tested with a fake.
 */
internal interface PrivySdkUser {
    val walletAddresses: List<String>

    /** Returns the new wallet's address. Throws the raw SDK error on failure. */
    suspend fun createEthereumWallet(): String

    /** Throws the raw SDK error on failure. */
    suspend fun refresh()
}
