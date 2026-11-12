package org.witness.proofmode.plugins.lp.attestation

import android.content.Context
import android.net.Uri
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.witness.proofmode.plugins.ipfscid.CidSidecarReadiness
import org.witness.proofmode.plugins.ipfscid.DefaultCidSidecarReadiness
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber

/**
 * Orchestrates the full Location Protocol attestation flow for a single media asset.
 *
 * CID sidecar wait/field-copy lives on [LocationProtocolCidPayloadEnricher]; this type
 * owns the attest → EAS → artifact path and constructs the enricher (test seam: inject
 * [CidSidecarReadiness]).
 */
class LocationProtocolAttestationCoordinator(
    private val storageProvider: StorageProvider,
    private val easManager: EASAttestationManager,
    cidSidecarReadiness: CidSidecarReadiness = DefaultCidSidecarReadiness(),
) {

    private val artifactStore = LocationProtocolArtifactStore(storageProvider)

    private val cidPayloadEnricher = LocationProtocolCidPayloadEnricher(
        storageProvider = storageProvider,
        cidSidecarReadiness = cidSidecarReadiness,
    )

    suspend fun attestOffchain(
        mediaHash: String,
        mediaUri: Uri,
        context: Context,
        memo: String = ""
    ): Result<LocationProtocolAttestationResult> = withContext(Dispatchers.IO) {
        try {
            val payload = cidPayloadEnricher.buildPayload(mediaHash, mediaUri, context, memo)
                ?: return@withContext Result.failure(
                    Exception("No proof found for media hash $mediaHash — cannot build LP payload")
                )

            val attestationResult = easManager.createOffchainLocationAttestation(payload)
                .getOrElse { return@withContext Result.failure(it) }

            try {
                artifactStore.saveOffchainAttestation(mediaHash, attestationResult.offchainPayloadJson)
            } catch (e: Exception) {
                Timber.e(e, "LP coordinator: failed to persist offchain attestation artifact for %s", mediaHash)
                return@withContext Result.failure(e)
            }

            Result.success(attestationResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "LP coordinator: unexpected error for hash %s", mediaHash)
            Result.failure(e)
        }
    }

    suspend fun attestOnchain(
        mediaHash: String,
        mediaUri: Uri,
        context: Context,
        memo: String = "",
        onchainConfirmed: ((String) -> Unit)? = null,
        onchainFailed: ((String) -> Unit)? = null,
    ): Result<LocationProtocolAttestationResult> = withContext(Dispatchers.IO) {
        try {
            val payload = cidPayloadEnricher.buildPayload(mediaHash, mediaUri, context, memo)
                ?: return@withContext Result.failure(
                    Exception("No proof found for media hash $mediaHash — cannot build LP payload")
                )

            Timber.i("LP coordinator: starting on-chain submit for hash=%s", mediaHash)

            val submitResult = easManager.submitOnchainLocationAttestation(payload)
                .getOrElse { return@withContext Result.failure(it) }

            val pendingResult = easManager.buildPendingAttestationResult(submitResult)

            withContext(NonCancellable) {
                try {
                    artifactStore.savePendingOnchainAttestation(
                        mediaHash,
                        pendingResult.offchainPayloadJson,
                    )
                } catch (e: Exception) {
                    Timber.e(e, "LP coordinator: failed to persist pending onchain artifact for %s", mediaHash)
                    throw e
                }
            }

            OnchainAttestationConfirmation.schedule(
                mediaHash = mediaHash,
                submitResult = submitResult,
                easManager = easManager,
                artifactStore = artifactStore,
                onConfirmed = onchainConfirmed,
                onFailed = onchainFailed,
            )

            Result.success(pendingResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "LP coordinator: unexpected error for hash %s", mediaHash)
            Result.failure(e)
        }
    }

    /**
     * Checks a pending record once instead of trusting it: writes the confirmed record when the
     * receipt shows the attestation, and deletes the pending record when a fresh submit is safe.
     */
    suspend fun recheckPendingOnchain(mediaHash: String): PendingOnchainRecheckOutcome = withContext(Dispatchers.IO) {
        val pendingJson = artifactStore.readPendingOnchainAttestation(mediaHash)
            ?: return@withContext PendingOnchainRecheckOutcome.Resubmit
        when (val recheck = easManager.recheckPendingOnchainAttestation(pendingJson)) {
            is PendingOnchainRecheckOutcome.Confirmed -> withContext(NonCancellable) {
                try {
                    artifactStore.saveOnchainAttestation(mediaHash, recheck.result.offchainPayloadJson)
                    recheck
                } catch (e: Exception) {
                    Timber.e(e, "LP coordinator: failed to persist re-checked onchain artifact for %s", mediaHash)
                    PendingOnchainRecheckOutcome.StillPending
                }
            }
            PendingOnchainRecheckOutcome.Resubmit -> {
                artifactStore.deletePendingOnchainAttestation(mediaHash)
                recheck
            }
            PendingOnchainRecheckOutcome.StillPending -> recheck
        }
    }
}
