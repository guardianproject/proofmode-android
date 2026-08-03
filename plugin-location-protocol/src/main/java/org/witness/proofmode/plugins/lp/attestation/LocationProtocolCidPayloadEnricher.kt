package org.witness.proofmode.plugins.lp.attestation

import android.content.Context
import android.net.Uri
import kotlin.coroutines.cancellation.CancellationException
import org.witness.proofmode.plugins.ipfscid.CidSidecarReadiness
import org.witness.proofmode.plugins.ipfscid.CidSidecarRefs
import org.witness.proofmode.plugins.ipfscid.DefaultCidSidecarReadiness
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber

/**
 * Builds an LP attestation payload from proof data, optionally applying CID sidecar
 * refs (`recipePayload` ← rootCid, `mediaData` ← media leaf CID).
 *
 * Owned by [LocationProtocolAttestationCoordinator]: wait/enrichment stays off
 * [LocationProtocolHelper] (baseline-only) and off `:app` callers.
 */
class LocationProtocolCidPayloadEnricher(
    private val storageProvider: StorageProvider,
    private val cidSidecarReadiness: CidSidecarReadiness = DefaultCidSidecarReadiness(),
) {

    /** Instance-scoped memo for F6b — one [CidSidecarReadiness.awaitReady] per proofSetHash. */
    private val cidRefsMemo = mutableMapOf<String, CidSidecarRefs?>()

    /**
     * Baseline via [LocationProtocolHelper.buildPayloadResult], then optional CID field copy.
     * Returns null only when proof data is missing (same as Helper).
     */
    suspend fun buildPayload(
        mediaHash: String,
        mediaUri: Uri,
        context: Context,
        memo: String = "",
    ): LocationProtocolPayload? {
        val built = LocationProtocolHelper.buildPayloadResult(
            mediaHash = mediaHash,
            mediaUri = mediaUri,
            contentResolver = context.contentResolver,
            storageProvider = storageProvider,
            memo = memo,
        ) ?: return null
        val refs = resolveCidRefs(mediaHash, context, built.captureMimeType)
        return if (refs != null) {
            built.payload.copy(
                recipePayload = arrayOf(refs.rootCid),
                mediaData = arrayOf(refs.mediaCid),
            )
        } else {
            built.payload
        }
    }

    private suspend fun resolveCidRefs(
        proofSetHash: String,
        context: Context,
        captureMimeType: String?,
    ): CidSidecarRefs? {
        if (cidRefsMemo.containsKey(proofSetHash)) {
            return cidRefsMemo[proofSetHash]
        }
        val refs = try {
            cidSidecarReadiness.awaitReady(
                context = context,
                storageProvider = storageProvider,
                proofSetHash = proofSetHash,
                mediaMimeType = captureMimeType,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "LP CID enricher: readiness failed for %s — using baseline", proofSetHash)
            null
        }
        cidRefsMemo[proofSetHash] = refs
        return refs
    }
}
