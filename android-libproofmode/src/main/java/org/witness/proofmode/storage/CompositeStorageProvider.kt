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

private data class OnDemandMark(
    val inclusion: MediaInclusion,
    val listener: StorageListener?,
)

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
    private val liveAutoUpload: () -> Boolean = { filebaseConfig?.autoUpload == true },
) : StorageProvider {

    companion object {
        private const val TAG = "CompositeStorageProvider"

        /** hash → on-demand inclusion consent + optional listener. */
        private val onDemandMarks = ConcurrentHashMap<String, OnDemandMark>()

        /** hash → (media content Uri, mime) for deferred proof-set leaf injection. */
        private val mediaByHash = ConcurrentHashMap<String, Pair<Uri, String?>>()

        /**
         * hash → media handle. Each handle reads [mediaByHash] on resolve and re-measures the file
         * every time, so a later [bindMedia] — or an in-place rewrite such as C2PA embedding — is
         * always reflected in the length the upload declares.
         */
        private val mediaSourceByHash = ConcurrentHashMap<String, ProofSetMediaSource>()

        fun clearOnDemandStateForTesting() {
            onDemandMarks.clear()
            mediaByHash.clear()
            mediaSourceByHash.clear()
        }
    }

    fun markOnDemand(hash: String, inclusion: MediaInclusion, listener: StorageListener? = null) {
        onDemandMarks[hash] = OnDemandMark(inclusion, listener)
    }

    /**
     * Drop the caller's listener once its upload reached a terminal outcome, keeping the inclusion
     * consent so late prefGated sidecars still flush.
     *
     * The listener is a UI callback owned by the tap that created it. Retaining it past the outcome
     * both pins that object for the process lifetime and replays a user-facing dialog on every
     * later automatic flush of the same hash.
     */
    private fun releaseOnDemandListener(hash: String) {
        onDemandMarks.computeIfPresent(hash) { _, mark -> mark.copy(listener = null) }
    }

    private fun shouldAutomaticFlush(hash: String): Boolean =
        deferProofSetUpload && (onDemandMarks.containsKey(hash) || liveAutoUpload())

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
        if (shouldAutomaticFlush(hash)) tryFlush(hash)
    }

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {
        primaryProvider.saveStream(hash, identifier, stream, listener)

        if (deferProofSetUpload) {
            if (shouldAutomaticFlush(hash)) tryFlush(hash)
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
            if (shouldAutomaticFlush(hash)) tryFlush(hash)
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
            if (shouldAutomaticFlush(hash)) tryFlush(hash)
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
     * Attempt upload of proof-set artifacts. Delayed until required cores (and injected media
     * when INCLUDE_MEDIA) are present. PrefGated `.ots` / `.nostr` can trigger later uploads.
     *
     * @return `true` iff [ProofSetUploader.enqueueProofSetUpload] returned `true`.
     *         `false` on every early exit: `!deferProofSetUpload`, missing config/ctx/Filebase
     *         secondary/bindMedia entry/unusable mode, INCLUDE_MEDIA unresolved media,
     *         incomplete first-pass (`enqueueProofSetUpload` false), or stamp-peek skip
     *         (returns **before** enqueue).
     */
    private fun tryFlush(hash: String): Boolean {
        if (!deferProofSetUpload) return false
        val config = filebaseConfig ?: return false
        val ctx = appContext ?: return false
        val secondary = secondaryProvider as? FilebaseStorageProvider
            ?: return false

        val (_, mime) = mediaByHash[hash] ?: return false

        val mark = onDemandMarks[hash]
        val mode = config.resolveUploadMode()
        if (mode != FilebaseConfig.UploadMode.IPFS_DIRECTORY &&
            mode != FilebaseConfig.UploadMode.S3_MEMBERS
        ) {
            return false
        }
        val inclusion = mark?.inclusion ?: config.resolveMediaInclusionForAuto()

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
            return false
        }

        // If media is too large, upload sidecars only (unmarked auto-upload path only).
        var adjustedInclusion = inclusion
        var adjustedMediaSource = mediaSource
        if (mark == null && inclusion == MediaInclusion.INCLUDE_MEDIA) {
            val length = mediaSource?.resolve()?.length
            if (length != null && !FilebaseConfig.isWithinFilebaseMediaLimit(length)) {
                adjustedInclusion = MediaInclusion.SIDECARS_ONLY
                adjustedMediaSource = null
            }
        }

        val onDisk = primaryProvider.getProofSet(hash)
            .mapNotNull { ProofSetMembershipPolicy.fromProofSetUri(it) }
        val memberBasenames = onDisk
            .filter { ProofSetMembershipPolicy.isManifestMember(ctx, hash, it) }
            .toSet()
        val candidate = ProofSetUploader.buildMembershipStamp(
            hash, mode, adjustedInclusion, memberBasenames, mime,
        )
        if (candidate == ProofSetUploader.lastUploadedMembership(hash)) {
            return false
        }

        return ProofSetUploader.enqueueProofSetUpload(
            ctx,
            hash,
            primaryProvider,
            secondary,
            adjustedMediaSource,
            mode,
            adjustedInclusion,
            object : StorageListener {
                override fun saveSuccessful(resultHash: String?, uri: String?) {
                    mark?.listener?.saveSuccessful(resultHash, uri)
                    releaseOnDemandListener(hash)
                    Log.d(TAG, "Deferred proof-set upload succeeded for $hash at: $uri")
                    tryFlush(hash)
                }

                override fun saveFailed(exception: Exception?) {
                    mark?.listener?.saveFailed(exception)
                    releaseOnDemandListener(hash)
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
