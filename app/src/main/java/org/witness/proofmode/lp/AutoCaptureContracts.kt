package org.witness.proofmode.lp

import android.net.Uri

enum class AutoCaptureSkipReason {
    LOCATION_UNAVAILABLE,
    AUTO_CAPTURE_WALLET_UNAVAILABLE,
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
    // Honored only for manual jobs: processJob checks manualLeg before calling it, so a callback
    // on an auto-capture job is ignored rather than raising a tap toast.
    val onComplete: ((Boolean) -> Unit)? = null,
)
