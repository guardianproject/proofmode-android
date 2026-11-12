package org.witness.proofmode.plugins.lp.attestation

/**
 * Result of an on-chain attestation submit (pre-receipt). Used to drive background confirmation.
 */
data class OnchainSubmitResult(
    val txHash: String,
    val easAddress: String,
    val chainIdStr: String,
    val rpcUrls: List<String>,
    val submittedAt: Long,
    val sponsorshipActive: Boolean,
    val onChainAttester: String,
)
