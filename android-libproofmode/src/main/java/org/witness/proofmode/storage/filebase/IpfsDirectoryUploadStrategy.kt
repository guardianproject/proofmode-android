package org.witness.proofmode.storage.filebase

import android.util.Log
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.proofset.DeferredArtifact
import org.witness.proofmode.storage.proofset.MediaInclusion
import org.witness.proofmode.storage.proofset.ProofSetUploadOutcome

/**
 * Strategy: IPFS directory upload + sidecar persistence.
 *
 * Owns unpin-before-add (best-effort) and persist. Facade owns Mutex/stamp/dispatch/listener
 * notify from [ProofSetUploadOutcome].
 */
internal object IpfsDirectoryUploadStrategy {
    private const val TAG = "IpfsDirectoryUploadStrategy"

    fun upload(
        primary: StorageProvider,
        filebase: FilebaseStorageProvider,
        hash: String,
        artifacts: List<DeferredArtifact>,
        mediaBasename: String?,
        mediaInclusion: MediaInclusion,
    ): ProofSetUploadOutcome {
        unpinPriorDirectoryIfPresent(filebase, primary, hash)

        val result = filebase.uploadDirectory(hash, artifacts, mediaBasename, listener = null)
            ?: return ProofSetUploadOutcome.Failed(
                IllegalStateException("IPFS directory upload transfer failed"),
            )
        val persisted = persistIpfsDirectorySuccess(
            primary,
            hash,
            result.directoryUri,
            mediaBasename,
            result.mediaLeafCid,
            mediaInclusion,
        )
        return if (persisted) {
            ProofSetUploadOutcome.Success(result.directoryUri)
        } else {
            ProofSetUploadOutcome.Failed(
                IllegalStateException("IPFS directory upload failed or sidecar persist skipped"),
            )
        }
    }

    private fun unpinPriorDirectoryIfPresent(
        filebase: FilebaseStorageProvider,
        primary: StorageProvider,
        hash: String,
    ) {
        val prior = FilebaseSidecarContract.readPriorDirectoryRootCid(primary, hash) ?: return
        val ok = filebase.unpinIpfsCid(prior)
        if (!ok) Log.w(TAG, "unpinIpfsCid failed for $prior; proceeding to overwrite")
    }

    /**
     * Always writes proofset URI on directory success.
     * [MediaInclusion.INCLUDE_MEDIA]: author image URI (leaf prefer / path fallback) or return false.
     * [MediaInclusion.SIDECARS_ONLY]: must not write image URI; leave any prior image untouched.
     */
    private fun persistIpfsDirectorySuccess(
        primary: StorageProvider,
        hash: String,
        directoryGatewayUri: String,
        mediaBasename: String?,
        mediaLeafCid: String?,
        mediaInclusion: MediaInclusion,
    ): Boolean {
        val directoryCid = FilebaseGatewayUris.parseGatewayRootCid(directoryGatewayUri) ?: return false
        primary.replaceText(
            hash,
            hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX,
            FilebaseGatewayUris.buildProofsetUri(directoryCid),
            null,
        )
        if (mediaInclusion == MediaInclusion.SIDECARS_ONLY) {
            return true
        }
        val imageUri = when {
            !mediaLeafCid.isNullOrBlank() -> FilebaseGatewayUris.buildLeafImageUri(mediaLeafCid)
            !mediaBasename.isNullOrBlank() ->
                FilebaseGatewayUris.buildImageUriUnderDirectory(directoryCid, mediaBasename)
            else -> return false
        }
        primary.replaceText(hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX, imageUri, null)
        return true
    }
}
