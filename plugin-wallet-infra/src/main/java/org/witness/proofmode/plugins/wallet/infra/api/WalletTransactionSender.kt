package org.witness.proofmode.plugins.wallet.infra.api

interface WalletTransactionSender {
    suspend fun sendTransaction(params: Map<String, Any?>): Map<String, Any>
}
