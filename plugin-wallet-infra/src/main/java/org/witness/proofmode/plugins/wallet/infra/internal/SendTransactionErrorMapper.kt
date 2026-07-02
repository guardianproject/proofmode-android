package org.witness.proofmode.plugins.wallet.infra.internal

import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionRejectedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionTimeoutException

/**
 * Maps transaction submission errors from providers to user-friendly [WalletTransactionException] instances.
 *
 * ERC-4337 error codes mapped:
 * - AA21: Paymaster deposit insufficient / budget exhaustion.
 * - AA25: Paymaster validation failed.
 * - AA30/AA31: Paymaster internal errors.
 * - AA40/AA41: Account validation errors.
 */
internal object SendTransactionErrorMapper {
    fun fromProviderError(message: String?, cause: Throwable?): WalletTransactionException {
        val normalized = message.orEmpty()
        val lowered = normalized.lowercase()

        return when {
            // ERC-4337 Paymaster errors
            lowered.contains("aa21") -> WalletProviderSubmitException(
                "Gas sponsorship limit reached. The paymaster cannot sponsor this transaction. " +
                "Please wait or contact your administrator.",
                cause
            )
            lowered.contains("aa25") -> WalletProviderSubmitException(
                "Paymaster validation failed. The transaction could not be verified by the gas sponsor.",
                cause
            )
            lowered.contains("aa30") || lowered.contains("aa31") -> WalletProviderSubmitException(
                "Paymaster is temporarily unavailable. Please try again later.",
                cause
            )
            lowered.contains("aa40") || lowered.contains("aa41") -> WalletProviderSubmitException(
                "Smart account validation failed. The account signature could not be verified on-chain.",
                cause
            )
            lowered.contains("sign_userop_failed") ||
                lowered.contains("paymaster stub") ||
                lowered.contains("signing for paymaster stub") -> WalletProviderSubmitException(
                "Gas sponsorship signing failed. Confirm your ZeroDev project has paymaster enabled for " +
                    "Sepolia, the kernel version matches V3.3, and the embedded wallet can sign messages.",
                cause
            )
            lowered.contains("insufficient funds") || lowered.contains("insufficient_funds") ||
            lowered.contains("insufficient gas") -> WalletProviderSubmitException(
                "Insufficient Gas: This operation requires native gas tokens (ETH) to execute. " +
                "Add funds or contact your administrator.",
                cause
            )
            lowered.contains("execution reverted") -> WalletProviderSubmitException(
                "On-chain attestation was rejected by the EAS contract. " +
                "Switch your wallet to Sepolia testnet, ensure the LP schema is registered on the active chain, " +
                "and confirm this proof has not already been attested on-chain.",
                cause
            )
            lowered.contains("rejected") || lowered.contains("user denied") ->
                WalletTransactionRejectedException(normalized, cause)
            lowered.contains("timeout") ||
                lowered.contains("timed out") ||
                lowered.contains("deadline exceeded") ->
                WalletTransactionTimeoutException(
                    message = normalized.ifBlank { "eth_sendTransaction timed out" },
                    cause = cause,
                )
            else ->
                WalletProviderSubmitException(
                    normalized.ifBlank { "eth_sendTransaction failed" },
                    cause,
                )
        }
    }
}
