package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import timber.log.Timber

internal object OnchainAttestationConfirmation {
    /** Every outcome ends in exactly one of [onConfirmed] or [onFailed], so the leg never stays running. */
    fun schedule(
        mediaHash: String,
        submitResult: OnchainSubmitResult,
        easManager: EASAttestationManager,
        artifactStore: LocationProtocolArtifactStore,
        onConfirmed: ((String) -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null,
    ) {
        LocationProtocolPlugin.requireApplicationScope().launch {
            when (val outcome = easManager.confirmOnchainLocationAttestation(submitResult)) {
                is OnchainConfirmationOutcome.Confirmed -> withContext(NonCancellable) {
                    try {
                        artifactStore.saveOnchainAttestation(mediaHash, outcome.result.offchainPayloadJson)
                    } catch (e: Exception) {
                        Timber.e(
                            e,
                            "OnchainAttestationConfirmation: failed to persist confirmed artifact hash=%s",
                            mediaHash,
                        )
                        onFailed?.invoke(mediaHash)
                        return@withContext
                    }
                    Timber.i(
                        "OnchainAttestationConfirmation: confirmed artifact saved hash=%s txHash=%s",
                        mediaHash,
                        submitResult.txHash,
                    )
                    onConfirmed?.invoke(mediaHash)
                }
                OnchainConfirmationOutcome.Reverted -> {
                    // Nothing landed, so the pending record goes and the leg can submit afresh.
                    artifactStore.deletePendingOnchainAttestation(mediaHash)
                    Timber.w(
                        "OnchainAttestationConfirmation: transaction reverted hash=%s txHash=%s",
                        mediaHash,
                        submitResult.txHash,
                    )
                    onFailed?.invoke(mediaHash)
                }
                // The transaction may still land (a poll timeout also arrives here), so the pending
                // record stays for a later re-check.
                is OnchainConfirmationOutcome.Failed -> {
                    Timber.w(
                        outcome.error,
                        "OnchainAttestationConfirmation: confirmation failed, pending kept hash=%s txHash=%s",
                        mediaHash,
                        submitResult.txHash,
                    )
                    onFailed?.invoke(mediaHash)
                }
            }
        }
    }
}
