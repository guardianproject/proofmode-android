package org.witness.proofmode.lp

import android.content.Context
import android.net.Uri
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationCoordinator

enum class AutoCaptureSkipReason {
    LOCATION_UNAVAILABLE,
    WALLET_UNAVAILABLE,
    NO_FOREGROUND_ACTIVITY,
}

fun interface AutoCaptureSkipListener {
    fun onSkip(reason: AutoCaptureSkipReason)
}

enum class LpManualLeg { OFFCHAIN, ONCHAIN }

internal data class AutoCaptureJob(
    val mediaHash: String,
    val mediaUri: Uri,
    val manualLeg: LpManualLeg? = null,
)
