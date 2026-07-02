package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import timber.log.Timber

internal object OnchainAttestationConfirmation {
    fun schedule(
        mediaHash: String,
        submitResult: OnchainSubmitResult,
        easManager: EASAttestationManager,
        artifactStore: LocationProtocolArtifactStore,
        onConfirmed: ((String) -> Unit)? = null,
    ) {
        LocationProtocolPlugin.requireApplicationScope().launch {
            easManager.confirmOnchainLocationAttestation(submitResult)
                .onSuccess { confirmed ->
                    withContext(NonCancellable) {
                        try {
                            artifactStore.saveOnchainAttestation(
                                mediaHash,
                                confirmed.offchainPayloadJson,
                            )
                            onConfirmed?.invoke(mediaHash)
                            Timber.i(
                                "OnchainAttestationConfirmation: confirmed artifact saved hash=%s txHash=%s",
                                mediaHash,
                                submitResult.txHash,
                            )
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "OnchainAttestationConfirmation: failed to persist confirmed artifact hash=%s",
                                mediaHash,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    Timber.w(
                        error,
                        "OnchainAttestationConfirmation: background confirmation failed hash=%s txHash=%s",
                        mediaHash,
                        submitResult.txHash,
                    )
                }
        }
    }
}
