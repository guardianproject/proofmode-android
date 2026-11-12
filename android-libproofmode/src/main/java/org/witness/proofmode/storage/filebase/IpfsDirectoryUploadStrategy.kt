package org.witness.proofmode.storage.filebase

import android.util.Log
import org.witness.proofmode.storage.StorageListener
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

    /**
     * [mediaBasename] is null exactly when the upload is [MediaInclusion.SIDECARS_ONLY]. It stands
     * in for the inclusion flag so the image-sidecar fallback always has a basename to use.
     */
    fun upload(
        primary: StorageProvider,
        filebase: FilebaseStorageProvider,
        hash: String,
        artifacts: List<DeferredArtifact>,
        mediaBasename: String?,
    ): ProofSetUploadOutcome {
        unpinPriorDirectoryIfPresent(filebase, primary, hash)

        // The provider reports its exception only through the listener, then returns null. Capture
        // it here rather than forwarding the caller's listener: the provider also fires success
        // before persist, and the facade must be the only notifier.
        var providerFailure: Exception? = null
        val result = filebase.uploadDirectory(
            hash,
            artifacts,
            mediaBasename,
            listener = object : StorageListener {
                override fun saveSuccessful(resultHash: String?, uri: String?) = Unit

                override fun saveFailed(exception: Exception?) {
                    providerFailure = exception
                }
            },
        ) ?: return ProofSetUploadOutcome.Failed(providerFailure)
        persistIpfsDirectorySuccess(primary, hash, result.directoryUri, mediaBasename, result.mediaLeafCid)
        return ProofSetUploadOutcome.Success(result.directoryUri)
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
     * Always writes proofset URI on directory success. Sidecars-only (null [mediaBasename]) must not
     * write image URI; any prior image sidecar stays untouched.
     */
    private fun persistIpfsDirectorySuccess(
        primary: StorageProvider,
        hash: String,
        directoryGatewayUri: String,
        mediaBasename: String?,
        mediaLeafCid: String?,
    ) {
        primary.replaceText(
            hash,
            hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX,
            directoryGatewayUri,
            null,
        )
        if (mediaBasename == null) return
        val imageUri = if (!mediaLeafCid.isNullOrBlank()) {
            FilebaseGatewayUris.buildLeafImageUri(mediaLeafCid)
        } else {
            "$directoryGatewayUri/$mediaBasename"
        }
        primary.replaceText(hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX, imageUri, null)
    }
}
