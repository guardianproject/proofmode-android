package org.witness.proofmode.lp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber

private const val DOCUMENT_IMAGE =
    "content://com.android.providers.media.documents/document/image%3A"
private const val MEDIA_IMAGE = "content://media/external/images/media/"

fun canonicalMediaUriKey(uri: Uri): String {
    var key = uri.toString()
    if (key.contains(DOCUMENT_IMAGE)) {
        key = key.replace(DOCUMENT_IMAGE, MEDIA_IMAGE)
    }
    return key
}

fun hasArtifactForManualLeg(
    storage: StorageProvider,
    mediaHash: String,
    leg: LpManualLeg,
): Boolean = when (leg) {
    LpManualLeg.OFFCHAIN ->
        storage.proofIdentifierExists(mediaHash, "$mediaHash${LocationProtocolArtifactStore.OFFCHAIN_SUFFIX}")
    // A pending record is not done: the onchain leg re-checks it.
    LpManualLeg.ONCHAIN ->
        storage.proofIdentifierExists(mediaHash, "$mediaHash${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}")
}

/**
 * Enqueues each (uri, proof hash) item whose leg has no artifact yet and, once every item has an
 * outcome, calls [onTapDone] once with how many succeeded. An existing artifact counts ok without
 * any job; an enqueued item's outcome is its job's completion callback.
 * Two items with one hash both enqueue: the second job finds the first's artifact.
 */
suspend fun enqueueManualAttestForShareProof(
    appContext: Context,
    items: List<Pair<Uri, String>>,
    leg: LpManualLeg,
    storage: StorageProvider,
    onTapDone: (succeeded: Int, total: Int) -> Unit,
) = withContext(Dispatchers.IO) {
    val outcomes = ArrayList<CompletableDeferred<Boolean>>(items.size)
    for ((uri, hash) in items) {
        val outcome = CompletableDeferred<Boolean>()
        outcomes += outcome
        if (hasArtifactForManualLeg(storage, hash, leg)) {
            Timber.d("LP manual enqueue: skip existing artifact hash=%s leg=%s", hash, leg)
            outcome.complete(true)
            continue
        }
        AutoCaptureLocationAttestationOrchestrator.enqueueManual(
            appContext,
            uri,
            hash,
            leg,
            onComplete = { ok -> outcome.complete(ok) },
        )
    }
    onTapDone(outcomes.awaitAll().count { it }, items.size)
}
