package org.witness.proofmode.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseStorageProvider
import org.witness.proofmode.storage.proofset.MediaInclusion
import org.witness.proofmode.storage.proofset.ProofSetMediaSource
import org.witness.proofmode.storage.proofset.ProofSetMembershipPolicy
import org.witness.proofmode.storage.proofset.ProofSetUploader

/**
 * Primary + optional secondary storage. When [deferProofSetUpload] is true (Filebase auto-upload),
 * proof sidecars are written to primary only and a proof-set upload is flushed
 * via [ProofSetUploader] once first-pass membership is complete.
 *
 * The media leaf (`{hash}.jpg` / etc.) is **not** saved through [saveBytes]/[saveText] — it is
 * injected at upload time from a content [Uri]. Callers (MediaWatcher) must tip Composite off
 * with [bindMedia] so [tryFlush] can hand the uploader a stream over those bytes (INCLUDE_MEDIA)
 * and choose the leaf basename/MIME.
 */
class CompositeStorageProvider(
    private val primaryProvider: StorageProvider,
    private val secondaryProvider: StorageProvider? = null,
    private val appContext: Context? = null,
    private val deferProofSetUpload: Boolean = false,
    private val filebaseConfig: FilebaseConfig? = null,
) : StorageProvider {

    companion object {
        private const val TAG = "CompositeStorageProvider"
    }

    /** hash → (media content Uri, mime) for deferred proof-set leaf injection. */
    private val mediaByHash = ConcurrentHashMap<String, Pair<Uri, String?>>()

    /**
     * hash → media handle. Each handle reads [mediaByHash] on resolve and re-measures the file
     * every time, so a later [bindMedia] — or an in-place rewrite such as C2PA embedding — is
     * always reflected in the length the upload declares.
     */
    private val mediaSourceByHash = ConcurrentHashMap<String, ProofSetMediaSource>()

    /**
     * Stash the source media [Uri] + MIME for a proof-set [hash] before/while proof sidecars
     * are saved.
     *
     * **Why this exists (Composite-only):** deferred proof-set upload needs an injected
     * media leaf that never goes through [saveBytes]/[saveStream]/[saveText]. Without this
     * tip-off, [tryFlush] has no way to open the media or name `{hash}.<ext>`, so auto-upload
     * would never start. Safe to call when [deferProofSetUpload] is false (stash is unused;
     * flush is a no-op). Always stash regardless of [MediaInclusion] — [tryFlush] gates reads.
     *
     * Prefer calling from MediaWatcher.writeProof (all processUri/Bytes/FileDescriptor paths
     * funnel there) **before** the first proof sidecar save when possible.
     */
    fun bindMedia(hash: String, mediaUri: Uri, mimeType: String) {
        mediaByHash[hash] = mediaUri to mimeType
        if (deferProofSetUpload) tryFlush(hash)
    }

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {
        primaryProvider.saveStream(hash, identifier, stream, listener)

        if (deferProofSetUpload) {
            tryFlush(hash)
            return
        }

        secondaryProvider?.let { secondary ->
            try {
                if (stream.markSupported()) {
                    stream.reset()
                } else {
                    Log.w(TAG, "Stream doesn't support reset, secondary provider may get empty stream")
                }

                secondary.saveStream(hash, identifier, stream, object : StorageListener {
                    override fun saveSuccessful(hash: String?, uri: String?) {
                        Log.d(TAG, "Successfully saved $identifier to secondary storage at: $uri")
                        primaryProvider.replaceText(hash, "$identifier.uri", uri, null)
                    }

                    override fun saveFailed(exception: Exception?) {
                        Log.w(TAG, "Failed to save $identifier to secondary storage: ${exception?.message}")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to secondary provider", e)
            }
        }
    }

    override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener?) {
        primaryProvider.saveBytes(hash, identifier, data, listener)

        if (deferProofSetUpload) {
            tryFlush(hash)
            return
        }

        secondaryProvider?.saveBytes(hash, identifier, data, object : StorageListener {
            override fun saveSuccessful(hash: String?, uri: String?) {
                Log.d(TAG, "Successfully saved $identifier to secondary storage at: $uri")
                primaryProvider.replaceText(hash, "$identifier.uri", uri, null)
            }

            override fun saveFailed(exception: Exception?) {
                Log.w(TAG, "Failed to save $identifier to secondary storage: ${exception?.message}")
            }
        })
    }

    override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {
        primaryProvider.saveText(hash, identifier, data, listener)

        if (deferProofSetUpload) {
            tryFlush(hash)
            return
        }

        secondaryProvider?.saveText(hash, identifier, data, object : StorageListener {
            override fun saveSuccessful(hash: String?, uri: String?) {
                Log.d(TAG, "Successfully saved text $identifier to secondary storage at: $uri")
                primaryProvider.replaceText(hash, "$identifier.uri", uri, null)
            }

            override fun saveFailed(exception: Exception?) {
                Log.w(TAG, "Failed to save text $identifier to secondary storage: ${exception?.message}")
            }
        })
    }

    /**
     * Tip / single-value overwrite on primary only.
     *
     * Unlike [saveText], this must not fan out to secondary or record a nested
     * `"$identifier.uri"` tip — callers use [replaceText] *to write* those tips
     * (and other local-only values). Mirroring would upload tip contents to S3 and
     * create `*.uri.uri` files.
     */
    override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener?) {
        primaryProvider.replaceText(hash, identifier, data, listener)
    }

    /**
     * Attempt upload of proof-set artifacts. The upload is delayed until all defined [ArtifactRule.RequiredCore], set
     * in [ProofSetMembershipPolicy.RULES] are present in the primary storage space. Once the condition is met, the
     * upload will be initiated. Any proof set artifacts defined in [ArtifactRule.PrefGated], can be triggered for later 
     * uploads once the files are present.
     *
     * This method supports the different upload modes defined in [FilebaseConfig.UploadMode].
     *  - IPFS upload strategy: This strategy will group all proof set artifacts into a single directory and upload it to IPFS.
     *  - S3 upload strategy: This strategy will upload each proof set artifact individually to S3.
     * 
     * Requires a prior [bindMedia] entry for [hash]; otherwise returns immediately.
     * Gates media stream opens by [MediaInclusion] from [filebaseConfig].
     */
    private fun tryFlush(hash: String) {
        if (!deferProofSetUpload) return
        val config = filebaseConfig ?: return
        val ctx = appContext ?: return
        val secondary = secondaryProvider as? FilebaseStorageProvider ?: return

        val (_, mime) = mediaByHash[hash] ?: return

        val mode = config.resolveUploadMode()
        if (mode != FilebaseConfig.UploadMode.IPFS_DIRECTORY &&
            mode != FilebaseConfig.UploadMode.S3_MEMBERS
        ) {
            return
        }
        val inclusion = config.resolveMediaInclusionForAuto()

        // Media is passed as a re-openable handle, never as bytes: tryFlush runs on every sidecar
        // save, and reading a capture in here OOM'd the process on large video.
        // SIDECARS_ONLY passes no source at all, so the media Uri is never opened.
        val mediaSource = when (inclusion) {
            MediaInclusion.INCLUDE_MEDIA -> mediaSourceByHash.computeIfAbsent(hash) {
                ProofSetMediaSource.fromUriProvider(ctx) { mediaByHash[hash] }
            }
            MediaInclusion.SIDECARS_ONLY -> null
        }
        if (inclusion == MediaInclusion.INCLUDE_MEDIA && mediaSource?.resolve() == null) {
            Log.w(TAG, "Media unavailable for deferred upload of $hash; not flushing")
            return
        }

        val onDisk = primaryProvider.getProofSet(hash)
            .mapNotNull { ProofSetMembershipPolicy.fromProofSetUri(it) }
        val memberBasenames = onDisk
            .filter { ProofSetMembershipPolicy.isManifestMember(ctx, hash, it) }
            .toSet()
        val candidate = ProofSetUploader.buildMembershipStamp(
            hash, mode, inclusion, memberBasenames, mime,
        )
        if (candidate == ProofSetUploader.lastUploadedMembership(hash)) return

        ProofSetUploader.enqueueProofSetUpload(
            ctx,
            hash,
            primaryProvider,
            secondary,
            mediaSource,
            mode,
            inclusion,
            object : StorageListener {
                override fun saveSuccessful(resultHash: String?, uri: String?) {
                    Log.d(TAG, "Deferred proof-set upload succeeded for $hash at: $uri")
                    tryFlush(hash)
                }

                override fun saveFailed(exception: Exception?) {
                    Log.w(TAG, "Deferred proof-set upload failed: ${exception?.message}")
                }
            },
        )
    }

    // All read operations delegate to primary provider only
    override fun getInputStream(hash: String, identifier: String): InputStream? {
        return primaryProvider.getInputStream(hash, identifier)
    }

    override fun proofExists(hash: String): Boolean {
        return primaryProvider.proofExists(hash)
    }

    override fun proofIdentifierExists(hash: String, identifier: String): Boolean {
        return primaryProvider.proofIdentifierExists(hash, identifier)
    }

    override fun getProofSet(hash: String): ArrayList<Uri> {
        return primaryProvider.getProofSet(hash)
    }

    override fun getProofItem(uri: Uri): InputStream? {
        return primaryProvider.getProofItem(uri)
    }
}
