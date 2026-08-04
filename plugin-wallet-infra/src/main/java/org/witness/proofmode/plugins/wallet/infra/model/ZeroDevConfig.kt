package org.witness.proofmode.plugins.wallet.infra.model

import org.witness.proofmode.plugins.wallet.infra.BuildConfig

data class ZeroDevConfig(
    val projectId: String,
    val bundlerUrl: String,
    val paymasterUrl: String,
    val isSponsorshipEnabled: Boolean = BuildConfig.FEATURE_SPONSORSHIP_ENABLED,
)
