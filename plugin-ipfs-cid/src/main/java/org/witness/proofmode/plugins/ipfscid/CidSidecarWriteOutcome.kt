package org.witness.proofmode.plugins.ipfscid

import timber.log.Timber

sealed interface CidSidecarWriteOutcome {
    data object SkippedGateOff : CidSidecarWriteOutcome
    data object SkippedEmptyManifest : CidSidecarWriteOutcome
    data class Success(val rootCid: String) : CidSidecarWriteOutcome
    data class Failed(val reason: String) : CidSidecarWriteOutcome

    companion object {
        fun logAtBoundary(proofSetHash: String, outcome: CidSidecarWriteOutcome) {
            when (outcome) {
                SkippedGateOff -> Timber.d("CID sidecar skipped (gate off) for %s", proofSetHash)
                SkippedEmptyManifest -> Timber.d("CID sidecar skipped (empty manifest) for %s", proofSetHash)
                is Success -> Timber.i("CID sidecar written for %s root=%s", proofSetHash, outcome.rootCid)
                is Failed -> Timber.w("CID sidecar failed for %s: %s", proofSetHash, outcome.reason)
            }
        }
    }
}
