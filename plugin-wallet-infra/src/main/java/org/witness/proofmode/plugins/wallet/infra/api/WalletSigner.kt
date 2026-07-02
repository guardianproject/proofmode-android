package org.witness.proofmode.plugins.wallet.infra.api

interface WalletSigner {
    /** The signer's Ethereum address (e.g. Privy embedded wallet address). */
    val address: String
    suspend fun signTypedData(typedDataJson: String): String
}
