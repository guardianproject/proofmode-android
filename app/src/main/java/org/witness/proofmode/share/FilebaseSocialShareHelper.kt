package org.witness.proofmode.share

import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseGatewayUris
import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import org.witness.proofmode.storage.filebase.FilebaseStorageProvider
import org.witness.proofmode.storage.proofset.DeferredArtifact
import org.witness.proofmode.storage.proofset.ProofSetMediaSource
import org.witness.proofmode.storage.proofset.ProofSetMembershipPolicy
import org.witness.proofmode.storage.StorageProvider

data class SocialVerifyLadderResult(
    val verifyUrl: String?,
    val leafAddFailed: Boolean = false,
)

object FilebaseSocialShareHelper {

    fun readProofText(primary: StorageProvider, hash: String, identifier: String): String? =
        primary.getInputStream(hash, identifier)?.bufferedReader()?.use { it.readText() }?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Derives and persists a gateway image URI from an already-present IPFS directory root sidecar.
     * Returns the derived URL, or null if media was not in the pin set, no directory URI is available,
     * or the CID cannot be parsed.
     *
     * MVP: [mediaWasInPinSet] defaults false — no invent from proofset-only SIDECARS_ONLY pins
     * until a real media-in-set disk marker exists for callers to pass true.
     *
     * Extracted to allow focused unit testing without Android framework types (ContentResolver/Uri).
     */
    internal fun deriveAndPersistFromDirectory(
        primary: StorageProvider,
        hash: String,
        mime: String?,
        mediaWasInPinSet: Boolean = false,
    ): String? {
        if (!mediaWasInPinSet) return null
        if (!FilebaseSidecarContract.hasIpfsDirectoryUri(primary, hash)) return null
        val proofset = readProofText(primary, hash, hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX)
        val cid = proofset?.let { FilebaseGatewayUris.parseGatewayRootCid(it) } ?: return null
        val derived = FilebaseGatewayUris.buildImageUriUnderDirectory(
            cid,
            ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, mime),
        )
        primary.replaceText(hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX, derived, null)
        return derived
    }

    /**
     * Reuses the media leaf of an already-pinned proof-set directory, when there is one.
     *
     * [deriveAndPersistFromDirectory] cannot invent this URL because nothing on disk records whether
     * the pin included the media. Asking IPFS for the directory's links settles it for the cost of
     * one metadata request — and a hit means the bytes are already stored, so the upload below is
     * skipped entirely. Returns null when there is no directory sidecar, the RPC has no `/ls`, or
     * the pin was sidecars-only.
     */
    internal fun resolveFromPinnedDirectory(
        primary: StorageProvider,
        filebase: FilebaseStorageProvider,
        hash: String,
        mime: String?,
    ): String? {
        val rootCid = FilebaseSidecarContract.readPriorDirectoryRootCid(primary, hash) ?: return null
        val basename = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, mime)
        val leafCid = filebase.findIpfsDirectoryLeafCid(rootCid, basename) ?: return null
        val leafUri = FilebaseGatewayUris.buildLeafImageUri(leafCid)
        primary.replaceText(hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX, leafUri, null)
        return leafUri
    }

    /**
     * Overview display URL for the media image link. Never invents from [proofsetUrl] alone —
     * after SIDECARS_ONLY pins only an existing image sidecar is shown.
     */
    internal fun overviewFilebaseImageUrl(
        proofsetUrl: String?,
        imageUrl: String?,
    ): String? = imageUrl?.takeIf { it.isNotBlank() }

    /**
     * Verify-URL ladder: existing image sidecar → directory-derived URL → media leaf already in the
     * pinned directory → a fresh leaf upload. Everything above the last rung answers from bytes
     * that are already stored, so the upload runs only when the media is genuinely not on IPFS.
     *
     * [media] is a handle, never bytes: social share runs against the original capture, and reading
     * one into a `ByteArray` OOM'd the process on large video.
     */
    fun resolveSocialVerifyUrl(
        primary: StorageProvider,
        filebase: FilebaseStorageProvider?,
        config: FilebaseConfig,
        hash: String,
        media: ProofSetMediaSource,
        mime: String?,
    ): SocialVerifyLadderResult {
        readProofText(primary, hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX)?.let {
            return SocialVerifyLadderResult(it)
        }

        deriveAndPersistFromDirectory(primary, hash, mime)?.let { derived ->
            return SocialVerifyLadderResult(derived)
        }

        var leafAddFailed = false
        if (config.hasIpfsAccess() && filebase != null) {
            resolveFromPinnedDirectory(primary, filebase, hash, mime)?.let { pinned ->
                return SocialVerifyLadderResult(pinned)
            }

            val resolved = media.resolve() ?: return SocialVerifyLadderResult(null)
            val basename = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, mime)
            val leafUri = filebase.uploadFileIpfs(
                DeferredArtifact(
                    basename,
                    resolved.mimeType ?: mime,
                    resolved.length,
                ) { resolved.openStream() },
            )
            if (leafUri != null) {
                readProofText(primary, hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX)?.let {
                    return SocialVerifyLadderResult(it)
                }
                deriveAndPersistFromDirectory(primary, hash, mime)?.let { derived ->
                    return SocialVerifyLadderResult(derived)
                }
                primary.replaceText(
                    hash,
                    hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX,
                    leafUri,
                    null,
                )
                return SocialVerifyLadderResult(leafUri)
            }
            leafAddFailed = true
        }

        return SocialVerifyLadderResult(null, leafAddFailed)
    }
}
