package org.witness.proofmode.plugins.wallet.infra.model

data class ZeroDevConfig(
    val projectId: String,
    val bundlerUrl: String,
    val paymasterUrl: String,
    val isSponsorshipEnabled: Boolean = true,
)
