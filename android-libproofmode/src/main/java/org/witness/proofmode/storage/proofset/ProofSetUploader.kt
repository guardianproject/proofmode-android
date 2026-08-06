package org.witness.proofmode.storage.proofset

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import org.witness.proofmode.storage.filebase.FilebaseStorageProvider
import org.witness.proofmode.storage.filebase.IpfsDirectoryUploadStrategy
import org.witness.proofmode.storage.filebase.S3MembersUploadStrategy

/**
 * Assembles first-pass proof-set members + media and uploads via Filebase IPFS directory
 * or per-member S3 [FilebaseStorageProvider.saveArtifact].
 *
 * Members are carried as [DeferredArtifact] stream handles, never as buffers — see that class for
 * why the media leaf must not be materialized.
 *
 * Facade: owns Mutex-per-hash coordination, membership stamp compare/advance,
 * Strategy dispatch, and sole UX listener notify from [ProofSetUploadOutcome].
 * IPFS Strategy owns unpin + persist on all IPFS paths.
 * Sidecar naming/read helpers: [FilebaseSidecarContract].
 * MIME mapping: [ProofSetContentTypes].
 *
 * ## Coordinator design
 *
 * - [processScope] is process-lifetime — do NOT cancel it from an Activity or ViewModel.
 *   It uses [SupervisorJob] so one hash's failure or cancellation does not cancel other hashes'
 *   in-flight work.
 * - [mutexByHash] and [lastUploadedMembershipByHash] are process-lifetime in-memory maps.
 *   They are NOT persisted across process death. Their entries are created on first use and
 *   are never removed (Mutex objects are cheap; entries accumulate proportionally to unique
 *   hashes seen in the process lifetime).
 * - Each [Mutex] in [mutexByHash] is owned by [withLock] only. There are no manual
 *   `lock()`/`unlock()` calls outside that block — [withLock] releases in its own `finally`.
 */
object ProofSetUploader {
    private const val TAG = "ProofSetUploader"

    private val lastUploadedMembershipByHash = ConcurrentHashMap<String, MembershipStamp>()

    /**
     * Process-lifetime [CoroutineScope] for per-hash upload pipelines.
     *
     * Declared as `var` to allow dispatcher injection in unit tests via [clearMapsForTesting].
     * Production code never writes this field.
     */
    private var processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One [Mutex] per hash. Serializes the full pipeline (reassemble → stamp → upload →
     * persist sidecars → advance stamp) for each hash independently.
     *
     * A second caller whose coroutine suspends at [Mutex.withLock] will pick up fresh storage
     * state after acquiring, naturally handling membership changes that occurred mid-flight.
     */
    private val mutexByHash = ConcurrentHashMap<String, Mutex>()

    /** Last enqueue args captured for unit tests (Composite media-source contract). */
    @Volatile
    internal var lastEnqueueForTesting: EnqueueCapture? = null

    internal data class EnqueueCapture(
        val mediaLength: Long?,
        val mediaMimeType: String?,
        val mode: FilebaseConfig.UploadMode,
        val mediaInclusion: MediaInclusion,
        val hasMediaSource: Boolean,
    )

    internal fun clearMapsForTesting(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        processScope.cancel()
        processScope = CoroutineScope(SupervisorJob() + dispatcher)
        lastUploadedMembershipByHash.clear()
        mutexByHash.clear()
        lastEnqueueForTesting = null
    }

    /** Process-scoped stamp for post/pre-enqueue compare. Production-safe name. */
    fun lastUploadedMembership(hash: String): MembershipStamp? = lastUploadedMembershipByHash[hash]

    /**
     * Shared entry for Composite + Share.
     *
     * Returns `true` ("accepted/scheduled") only when first-pass membership is **complete**
     * (required names present + media available when [mediaInclusion] is [MediaInclusion.INCLUDE_MEDIA]).
     * Returns `false` ("not ready") — without invoking [listener].[saveFailed][StorageListener.saveFailed]
     * — when membership is incomplete. Unreadable members are **not** a pre-enqueue rejection; after
     * `started=true`, post-acquire assemble failure or stamp-match with media that no longer
     * resolves (INCLUDE_MEDIA) → `saveFailed`.
     *
     * The actual upload runs asynchronously on [processScope]. Listeners receive outcomes
     * asynchronously after the coroutine acquires the per-hash [Mutex], reassembles membership
     * from fresh storage state, and dispatches to the appropriate Strategy.
     */
    fun enqueueProofSetUpload(
        context: Context,
        hash: String,
        primary: StorageProvider,
        filebase: FilebaseStorageProvider,
        mediaSource: ProofSetMediaSource?,
        mode: FilebaseConfig.UploadMode,
        mediaInclusion: MediaInclusion,
        listener: StorageListener?,
    ): Boolean {
        val capturedMedia = resolveMedia(mediaSource, mediaInclusion)
        lastEnqueueForTesting = EnqueueCapture(
            mediaLength = capturedMedia?.length,
            mediaMimeType = capturedMedia?.mimeType,
            mode = mode,
            mediaInclusion = mediaInclusion,
            hasMediaSource = mediaSource != null,
        )
        if (mode == FilebaseConfig.UploadMode.NONE) return false

        // Pre-enqueue gate: completeness only (names + media present when required). Does not assemble.
        if (!isFirstPassReadyForEnqueue(context, hash, primary, capturedMedia, mediaInclusion)) {
            return false
        }

        processScope.launch {
            val mutex = mutexByHash.computeIfAbsent(hash) { Mutex() }
            // Mutex held for the full pipeline: acquire → reassemble fresh → stamp compare →
            // sync Strategy upload → advance stamp on Success. withLock releases in finally.
            mutex.withLock {
                // Re-resolve media under the lock so a rebind while we waited takes effect. The
                // handle is a length + stream opener; media bytes are never held here.
                val media = resolveMedia(mediaSource, mediaInclusion)
                val resolvedMime = media?.mimeType
                // Re-list primary under the lock so membership reflects sidecars that landed while
                // waiting for the mutex, then rebuild the candidate stamp from that snapshot.
                val onDisk = primary.getProofSet(hash)
                    .mapNotNull { ProofSetMembershipPolicy.fromProofSetUri(it) }
                    .toSet()
                val memberBasenames = onDisk
                    .filter { ProofSetMembershipPolicy.isManifestMember(context, hash, it) }
                    .toSet()
                val candidate = buildMembershipStamp(
                    hash, mode, mediaInclusion, memberBasenames, resolvedMime,
                )

                // Stamp comparison:
                //   - Equal: no re-upload. Assembles members just to verify readability, then notifies
                //            success with the existing Filebase URI sidecar (notifyStampSkipSuccess).
                //            This is the "already done" path after a successful upload when tryFlush races again.
                //   - Not equal: first-pass re-check → assemble → Strategy upload → advance stamp on success.
                if (candidate == lastUploadedMembershipByHash[hash]) {
                    // Stamp-skip fail-closed: always assemble included members.
                    // INCLUDE_MEDIA: also fail-closed on media that no longer resolves.
                    // SIDECARS_ONLY: do not fail solely for missing media.
                    if (mediaInclusion == MediaInclusion.INCLUDE_MEDIA && media == null) {
                        listener?.saveFailed(
                            IllegalStateException("Stamp match but injected media missing or empty"),
                        )
                        return@withLock
                    }
                    val readable = assembleArtifacts(
                        context, hash, onDisk, media, primary, mediaInclusion,
                    )
                    if (readable == null) {
                        listener?.saveFailed(
                            IllegalStateException("Stamp match but proof set members unreadable"),
                        )
                        return@withLock
                    }
                    notifyStampSkipSuccess(primary, hash, listener)
                    return@withLock
                }

                // Re-check first-pass completeness on the post-acquire snapshot. Catches cases where
                // primary/media became incomplete while waiting (e.g. required core missing, or
                // injected media unreadable) — not "another upload removed members."
                if (!ProofSetMembershipPolicy.isFirstPassComplete(
                        context, hash, onDisk, mediaInclusion, media != null,
                    )
                ) {
                    listener?.saveFailed(
                        IllegalStateException("Proof set first-pass became incomplete before upload"),
                    )
                    return@withLock
                }

                // Build the upload set for the onDisk membership snapshot already taken under the
                // lock (plus the resolved media handle). Does not re-list primary; fails closed if
                // any member is unreadable.
                val artifacts = assembleArtifacts(
                    context, hash, onDisk, media, primary, mediaInclusion,
                )
                if (artifacts == null) {
                    listener?.saveFailed(
                        IllegalStateException("Failed to assemble proof set artifacts after mutex acquire"),
                    )
                    return@withLock
                }

                val mediaName = when (mediaInclusion) {
                    MediaInclusion.INCLUDE_MEDIA ->
                        ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, resolvedMime)
                    MediaInclusion.SIDECARS_ONLY -> null
                }

                val outcome = when (mode) {
                    FilebaseConfig.UploadMode.IPFS_DIRECTORY ->
                        IpfsDirectoryUploadStrategy.upload(
                            primary, filebase, hash, artifacts, mediaName, mediaInclusion,
                        )
                    FilebaseConfig.UploadMode.S3_MEMBERS ->
                        S3MembersUploadStrategy.upload(
                            primary, filebase, hash, artifacts, mediaName, mediaInclusion,
                        )
                    FilebaseConfig.UploadMode.NONE -> ProofSetUploadOutcome.Failed(null)
                }
                when (outcome) {
                    is ProofSetUploadOutcome.Success -> {
                        lastUploadedMembershipByHash[hash] = candidate
                        listener?.saveSuccessful(hash, outcome.resultUri) // sole success notify
                    }
                    is ProofSetUploadOutcome.Failed -> {
                        listener?.saveFailed(outcome.error) // sole failure notify
                    }
                }
            }
        }

        return true
    }

    /**
     * Pre-enqueue readiness gate. Runs synchronously on the caller's thread before scheduling
     * the coroutine. Returns `false` (without firing [StorageListener.saveFailed]) when the
     * first-pass membership is incomplete (required names missing / media missing under INCLUDE_MEDIA).
     *
     * Does **not** call [assembleArtifacts] — unreadability is handled post-acquire after
     * `started=true` via `saveFailed`.
     */
    private fun isFirstPassReadyForEnqueue(
        context: Context,
        hash: String,
        primary: StorageProvider,
        media: ResolvedMedia?,
        mediaInclusion: MediaInclusion,
    ): Boolean {
        val onDisk = primary.getProofSet(hash)
            .mapNotNull { ProofSetMembershipPolicy.fromProofSetUri(it) }
            .toSet()
        return ProofSetMembershipPolicy.isFirstPassComplete(
            context, hash, onDisk, mediaInclusion, media != null,
        )
    }

    /**
     * Snapshot [mediaSource] for one pass, or `null` when it is absent, unresolvable, or not wanted.
     *
     * Under [MediaInclusion.SIDECARS_ONLY] the source is never touched — that is what keeps the
     * media Uri unopened when the user has auto-include off.
     */
    private fun resolveMedia(
        mediaSource: ProofSetMediaSource?,
        mediaInclusion: MediaInclusion,
    ): ResolvedMedia? {
        if (mediaInclusion != MediaInclusion.INCLUDE_MEDIA) return null
        return try {
            mediaSource?.resolve()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve media for proof-set upload", e)
            null
        }
    }

    /**
     * SIDECARS_ONLY: [media] may be null; media leaf omitted.
     * INCLUDE_MEDIA: a resolved handle is required (caller fail-closes before call).
     *
     * Sidecars are read into memory — they are a few KB each. The media leaf contributes only its
     * length and a stream opener, so the upload streams it rather than buffering it.
     */
    private fun assembleArtifacts(
        context: Context,
        hash: String,
        onDisk: Set<String>,
        media: ResolvedMedia?,
        primary: StorageProvider,
        mediaInclusion: MediaInclusion,
    ): List<DeferredArtifact>? {
        val artifacts = mutableListOf<DeferredArtifact>()
        for (id in ProofSetMembershipPolicy.manifestMemberBasenames(context, hash, onDisk)) {
            val bytes = primary.getInputStream(hash, id)?.use { it.readBytes() } ?: return null
            artifacts.add(DeferredArtifact.ofBytes(id, bytes, ProofSetContentTypes.contentTypeFor(id)))
        }
        if (mediaInclusion == MediaInclusion.INCLUDE_MEDIA) {
            val resolved = media ?: return null
            val mediaName = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, resolved.mimeType)
            artifacts.add(
                DeferredArtifact(
                    mediaName,
                    resolved.mimeType ?: "application/octet-stream",
                    resolved.length,
                ) { resolved.openStream() },
            )
        }
        artifacts.sortBy { it.identifier }
        return artifacts
    }

    /** Normative stamp builder shared with Composite tryFlush peek. */
    // Creates a manifest, representing the expected contents of a proof set.
    internal fun buildMembershipStamp(
        hash: String,
        mode: FilebaseConfig.UploadMode,
        mediaInclusion: MediaInclusion,
        memberBasenames: Set<String>,
        mediaMime: String?,
    ): MembershipStamp {
        val basenames = when (mediaInclusion) {
            MediaInclusion.INCLUDE_MEDIA ->
                memberBasenames + ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, mediaMime)
            MediaInclusion.SIDECARS_ONLY -> memberBasenames
        }
        return MembershipStamp(mode, mediaInclusion, basenames)
    }

    private fun readSidecarText(primary: StorageProvider, hash: String, suffix: String): String? =
        primary.getInputStream(hash, hash + suffix)
            ?.bufferedReader()?.use { it.readText() }?.trim()

    private fun notifyStampSkipSuccess(primary: StorageProvider, hash: String, listener: StorageListener?) {
        if (listener == null) return
        val uri = readSidecarText(primary, hash, FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX)
            ?: readSidecarText(primary, hash, FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX)
        listener.saveSuccessful(hash, uri)
    }
}
