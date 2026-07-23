package org.witness.proofmode.storage.filebase

import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.proofset.DeferredArtifact
import org.witness.proofmode.storage.proofset.MediaInclusion
import org.witness.proofmode.storage.proofset.ProofSetUploadOutcome

/**
 * Strategy: per-member S3 upload via [StorageProvider.saveBytes] + image URI sidecar persistence.
 *
 * Returns [ProofSetUploadOutcome]; does not advance the membership stamp or forward a UX listener.
 * Facade notifies from the Outcome.
 */
internal object S3MembersUploadStrategy {

    fun upload(
        primary: StorageProvider,
        filebase: FilebaseStorageProvider,
        hash: String,
        artifacts: List<DeferredArtifact>,
        mediaBasename: String?,
        mediaInclusion: MediaInclusion,
    ): ProofSetUploadOutcome {
        var mediaUri: String? = null
        var failed: Exception? = null
        for (artifact in artifacts) {
            if (failed != null) break
            filebase.saveBytes(hash, artifact.identifier, artifact.data, object : StorageListener {
                override fun saveSuccessful(resultHash: String?, uri: String?) {
                    if (mediaBasename != null && artifact.identifier == mediaBasename) {
                        mediaUri = uri
                    }
                }

                override fun saveFailed(exception: Exception?) {
                    failed = exception ?: RuntimeException("saveBytes failed")
                }
            })
        }
        if (failed != null) {
            return ProofSetUploadOutcome.Failed(failed)
        }
        return when (mediaInclusion) {
            MediaInclusion.SIDECARS_ONLY -> ProofSetUploadOutcome.Success(null)
            MediaInclusion.INCLUDE_MEDIA -> {
                val uri = mediaUri
                    ?: return ProofSetUploadOutcome.Failed(
                        IllegalStateException("S3 media member URI missing; Required image sidecar not authored"),
                    )
                primary.replaceText(
                    hash,
                    hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX,
                    uri,
                    null,
                )
                ProofSetUploadOutcome.Success(uri)
            }
        }
    }
}
