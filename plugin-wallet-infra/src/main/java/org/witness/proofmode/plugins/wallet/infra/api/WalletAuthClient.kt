package org.witness.proofmode.plugins.wallet.infra.api

interface WalletAuthClient {
    suspend fun sendEmailCode(email: String)
    suspend fun loginWithEmailCode(email: String, code: String)
    suspend fun sendSmsCode(phoneNumber: String)
    suspend fun loginWithSmsCode(phoneNumber: String, code: String)
}
