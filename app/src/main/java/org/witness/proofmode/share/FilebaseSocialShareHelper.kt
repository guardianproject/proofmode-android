package org.witness.proofmode.share

import android.content.ContentResolver
import android.net.Uri
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseGatewayUris
import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import org.witness.proofmode.storage.filebase.FilebaseStorageProvider
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
     * Overview display URL for the media image link. Never invents from [proofsetUrl] alone —
     * after SIDECARS_ONLY pins only an existing image sidecar is shown.
     */
    internal fun overviewFilebaseImageUrl(
        proofsetUrl: String?,
        imageUrl: String?,
    ): String? = imageUrl?.takeIf { it.isNotBlank() }

    fun resolveSocialVerifyUrl(
        primary: StorageProvider,
        filebase: FilebaseStorageProvider?,
        config: FilebaseConfig,
        hash: String,
        mediaUri: Uri,
        mime: String?,
        contentResolver: ContentResolver,
    ): SocialVerifyLadderResult {
        readProofText(primary, hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX)?.let {
            return SocialVerifyLadderResult(it)
        }

        deriveAndPersistFromDirectory(primary, hash, mime)?.let { derived ->
            return SocialVerifyLadderResult(derived)
        }

        var leafAddFailed = false
        if (config.hasIpfsAccess() && filebase != null) {
            val bytes = contentResolver.openInputStream(mediaUri)?.use { it.readBytes() }
                ?: return SocialVerifyLadderResult(null)
            val basename = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, mime)
            val leafUri = filebase.uploadFileIpfs(basename, bytes, mime)
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
