package org.witness.proofmode.lp

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.witness.proofmode.ProofMode
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
    LpManualLeg.OFFCHAIN -> {
        val offchainId = "$mediaHash${LocationProtocolArtifactStore.OFFCHAIN_SUFFIX}"
        val legacyId = "$mediaHash${LocationProtocolArtifactStore.LEGACY_SUFFIX}"
        storage.proofIdentifierExists(mediaHash, offchainId) ||
            storage.proofIdentifierExists(mediaHash, legacyId)
    }
    LpManualLeg.ONCHAIN -> {
        val onchainId = "$mediaHash${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}"
        val pendingId = "$mediaHash${LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX}"
        storage.proofIdentifierExists(mediaHash, onchainId) ||
            storage.proofIdentifierExists(mediaHash, pendingId)
    }
}

/**
 * IO fallback mirroring ShareProofActivity.proofExists hash path, but using the caller's
 * ContentResolver (production: applicationContext.contentResolver).
 */
fun resolveProofHashFromContent(
    contentResolver: android.content.ContentResolver,
    storage: StorageProvider,
    uri: Uri,
): String? {
    val key = canonicalMediaUriKey(uri)
    val mediaUri = Uri.parse(key)
    val hash = org.witness.proofmode.crypto.HashUtils.getSHA256FromFileContent(
        contentResolver.openInputStream(mediaUri),
    ) ?: return null
    if (storage.proofExists(hash) &&
        storage.proofIdentifierExists(hash, hash + ProofMode.PROOF_FILE_TAG)
    ) {
        return hash
    }
    return null
}

/**
 * Resolves media hash for attest enqueue.
 * @param proofExistsResolver Optional test override for IO fallback; production omits and uses
 *        [resolveProofHashFromContent] with [contentResolver].
 */
fun resolveMediaHashForAttest(
    storage: StorageProvider,
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    hashCache: MutableMap<String, String?>,
    proofExistsResolver: ((Uri, StorageProvider, android.content.ContentResolver) -> String?)? = null,
): String? {
    val key = canonicalMediaUriKey(uri)
    hashCache[key]?.let { return it }
    val resolved = proofExistsResolver?.invoke(uri, storage, contentResolver)
        ?: resolveProofHashFromContent(contentResolver, storage, uri)
    if (resolved != null) {
        hashCache[key] = resolved
    }
    return resolved
}

data class ManualAttestEnqueueResult(
    val enqueuedCount: Int,
    val hashMissCount: Int,
)

suspend fun enqueueManualAttestForShareProof(
    appContext: Context,
    uris: List<Uri>,
    leg: LpManualLeg,
    hashCache: MutableMap<String, String?>,
    storage: StorageProvider,
    proofExistsResolver: ((Uri, StorageProvider, android.content.ContentResolver) -> String?)? = null,
): ManualAttestEnqueueResult = withContext(Dispatchers.IO) {
    var enqueued = 0
    var hashMisses = 0
    for (uri in uris) {
        val hash = resolveMediaHashForAttest(
            storage = storage,
            contentResolver = appContext.contentResolver,
            uri = uri,
            hashCache = hashCache,
            proofExistsResolver = proofExistsResolver,
        )
        if (hash == null) {
            Timber.w("LP manual enqueue: no hash for uri=%s", uri)
            hashMisses++
            continue
        }
        if (hasArtifactForManualLeg(storage, hash, leg)) {
            Timber.d("LP manual enqueue: skip existing artifact hash=%s leg=%s", hash, leg)
            continue
        }
        AutoCaptureLocationAttestationOrchestrator.enqueueManual(
            appContext,
            uri,
            hash,
            leg,
        )
        enqueued++
    }
    ManualAttestEnqueueResult(enqueued, hashMisses)
}

fun collectShareProofMediaUris(
    intent: Intent,
    cleanUri: (Uri) -> Uri,
): List<Uri> {
    val action = intent.action
    return when {
        action == Intent.ACTION_SEND_MULTIPLE -> {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                .orEmpty()
                .map(cleanUri)
        }
        action == Intent.ACTION_SEND || action?.endsWith("SHARE_PROOF") == true -> {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
            listOfNotNull(uri?.let(cleanUri))
        }
        else -> emptyList()
    }
}
