package org.witness.proofmode.storage.proofset

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import java.io.InputStream
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.witness.proofmode.storage.AccumulatingStorageProvider
import org.witness.proofmode.storage.CompositeStorageProvider
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseConfig.UploadMode
import org.witness.proofmode.storage.filebase.FilebaseGatewayUris
import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import org.witness.proofmode.storage.filebase.FilebaseStorageProvider
import org.witness.proofmode.storage.filebase.FilebaseUploadResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetUploaderTest {
    private val hash = "abc123deadbeef"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        // Unconfined dispatcher: coroutines run synchronously on the calling thread, making all
        // upload state observable immediately after enqueueProofSetUpload returns without latches.
        // Mutex suspension still works correctly — a coroutine waiting on a held Mutex suspends
        // and resumes inline when the holder exits withLock (Unconfined resumes on releasing thread).
        ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)
    }

    private fun coreBasenames(): Set<String> =
        ProofSetMembershipPolicy.requiredCoreBasenames(hash)

    private fun primaryWithCore(
        streams: Map<String, ByteArray> = coreBasenames().associateWith { it.toByteArray() },
        proofNames: Set<String> = coreBasenames(),
    ): RecordingStorageProvider = RecordingStorageProvider(
        proofSetUris = ArrayList(proofNames.map { Uri.parse("file:///proof/$it") }),
        streams = streams,
    )

    private fun enqueueUpload(
        primary: StorageProvider,
        filebase: FilebaseStorageProvider,
        listener: StorageListener? = null,
        media: ProofSetMediaSource? = ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
        mode: UploadMode = UploadMode.IPFS_DIRECTORY,
        inclusion: MediaInclusion = MediaInclusion.INCLUDE_MEDIA,
        hash: String = this.hash,
    ): Boolean = ProofSetUploader.enqueueProofSetUpload(
        context,
        hash,
        primary,
        filebase,
        media,
        mode,
        inclusion,
        listener,
    )

    /**
     * Polls on the test thread. occupancyCount reads the current state, so a poll that starts
     * after the upload is released cannot miss the drop the way a late flow subscriber would.
     */
    private fun awaitOccupancyZero(hashes: Collection<String>, timeoutSeconds: Long = 5): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (hashes.any { ProofSetUploader.occupancyCount(it) != 0 }) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(10)
        }
        return true
    }

    @Test
    fun contentTypeFor_mapsKnownExtensions() {
        assertEquals("image/jpeg", ProofSetContentTypes.contentTypeFor("$hash.jpg"))
        assertEquals("text/csv", ProofSetContentTypes.contentTypeFor("$hash.csv"))
        assertEquals("application/octet-stream", ProofSetContentTypes.contentTypeFor("$hash.unknown"))
    }

    @Test
    fun ipfsMode_callsUploadDirectory_withMediaLeaf() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val media = byteArrayOf(1, 2, 3)
        val listener = RecordingListener()

        val started = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = ProofSetMediaSource.ofBytes(media, "image/jpeg"),
        )

        assertTrue(started)
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(0, filebase.saveArtifactCalls.size)

        val (callHash, artifacts) = filebase.uploadDirectoryCalls.single()
        assertEquals(hash, callHash)
        val ids = artifacts.map { it.identifier }
        assertTrue(ids.contains("$hash.jpg"))
        assertTrue(coreBasenames().all { it in ids })
        assertEquals(coreBasenames().size + 1, artifacts.size)

        val mediaArt = artifacts.single { it.identifier == "$hash.jpg" }
        assertTrue(mediaArt.readAllBytes().contentEquals(media))
        assertEquals("image/jpeg", mediaArt.contentType)
        assertEquals(ids.sorted(), ids)

        val gatewayUri = filebase.uploadDirectoryResultUri
        val cid = FilebaseGatewayUris.parseGatewayRootCid(gatewayUri)!!
        assertEquals(
            listOf(
                Triple(
                    hash,
                    hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX,
                    FilebaseGatewayUris.buildProofsetUri(cid),
                ),
                Triple(
                    hash,
                    hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX,
                    FilebaseGatewayUris.buildLeafImageUri(filebase.uploadDirectoryMediaLeafCid!!),
                ),
            ),
            primary.saveTextCalls,
        )
        assertEquals(listOf(hash to gatewayUri), listener.successes)
        assertTrue(filebase.unpinCalls.isEmpty())
    }

    @Test
    fun incompleteWhenMediaMissing_returnsFalse_noUpload() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()

        assertFalse(enqueueUpload(primary, filebase, media = null, mode = UploadMode.S3_MEMBERS))
        assertFalse(
            enqueueUpload(
                primary,
                filebase,
                media = ProofSetMediaSource.ofBytes(byteArrayOf(), "image/jpeg"),
                mode = UploadMode.S3_MEMBERS,
            ),
        )
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
        assertTrue(filebase.saveArtifactCalls.isEmpty())
    }

    @Test
    fun s3Mode_callsSaveBytes_oncePerArtifactIncludingMedia() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val media = byteArrayOf(4, 5, 6)
        val listener = RecordingListener()

        val started = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = ProofSetMediaSource.ofBytes(media, "image/png"),
            mode = UploadMode.S3_MEMBERS,
        )

        assertTrue(started)
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
        assertEquals(coreBasenames().size + 1, filebase.saveArtifactCalls.size)

        val identifiers = filebase.saveArtifactCalls.map { it.second }
        assertTrue("$hash.png" in identifiers)
        assertTrue(coreBasenames().all { it in identifiers })
        assertEquals(identifiers.sorted(), identifiers)

        val mediaCall = filebase.saveArtifactCalls.single { it.second == "$hash.png" }
        assertTrue(mediaCall.third.contentEquals(media))

        val mediaUri = "s3://bucket/$hash/$hash.png"
        assertEquals(1, listener.successes.size)
        assertEquals(hash to mediaUri, listener.successes.single())
        assertEquals(
            listOf(
                Triple(hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX, mediaUri),
            ),
            primary.saveTextCalls,
        )
    }

    private fun primaryWithDirectoryProvenance(
        priorCid: String,
        mediaBasename: String = "$hash.jpg",
        streams: Map<String, ByteArray> = coreBasenames().associateWith { it.toByteArray() },
        proofNames: Set<String> = coreBasenames(),
    ): RecordingStorageProvider {
        val proofsetUri = FilebaseGatewayUris.buildProofsetUri(priorCid)
        val imageUri = FilebaseGatewayUris.buildImageUriUnderDirectory(priorCid, mediaBasename)
        val sidecarStreams = mutableMapOf<String, ByteArray>()
        sidecarStreams.putAll(streams)
        sidecarStreams[hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX] = proofsetUri.toByteArray()
        sidecarStreams[hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX] = imageUri.toByteArray()
        return RecordingStorageProvider(
            proofSetUris = ArrayList(proofNames.map { Uri.parse("file:///proof/$it") }),
            streams = sidecarStreams,
        )
    }

    @Test
    fun ipfsReupload_unpinsPriorDirectoryCidOnce_thenUploads() {
        val priorCid = "bafyPriorRoot"
        val primary = primaryWithDirectoryProvenance(priorCid)
        val filebase = RecordingFilebaseStorageProvider(
            uploadDirectoryResultUri = "https://ipfs.filebase.io/ipfs/bafyNewRoot",
        )
        val media = byteArrayOf(1, 2, 3)

        val started = enqueueUpload(
            primary,
            filebase,
            media = ProofSetMediaSource.ofBytes(media, "image/jpeg"),
        )

        assertTrue(started)
        assertEquals(listOf(priorCid), filebase.unpinCalls)
        assertEquals(1, filebase.uploadDirectoryCalls.size)
    }

    @Test
    fun ipfsReupload_pollutedMultilineFilebaseUri_unpinsLatestCidOnly() {
        val oldCid = "bafyOldRoot"
        val midCid = "bafyMidRoot"
        val latestCid = "bafyLatestRoot"
        val mediaBasename = "$hash.jpg"
        val polluted =
            FilebaseGatewayUris.buildProofsetUri(oldCid) + "\n" +
                FilebaseGatewayUris.buildProofsetUri(midCid) + "\n" +
                FilebaseGatewayUris.buildProofsetUri(latestCid) + "\n"
        val imageUri = FilebaseGatewayUris.buildImageUriUnderDirectory(latestCid, mediaBasename)
        val streams = coreBasenames().associateWith { it.toByteArray() }.toMutableMap()
        streams[hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX] = polluted.toByteArray()
        streams[hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX] = imageUri.toByteArray()
        val primary = RecordingStorageProvider(
            proofSetUris = ArrayList(coreBasenames().map { Uri.parse("file:///proof/$it") }),
            streams = streams,
        )
        val filebase = RecordingFilebaseStorageProvider(
            uploadDirectoryResultUri = "https://ipfs.filebase.io/ipfs/bafyBrandNew",
        )

        assertTrue(
            enqueueUpload(
                primary,
                filebase,
                media = ProofSetMediaSource.ofBytes(byteArrayOf(1), "image/jpeg"),
            ),
        )

        assertEquals(listOf(latestCid), filebase.unpinCalls)
        assertFalse(filebase.unpinCalls.any { it.contains(":") || it.contains("https") })
    }

    @Test
    fun unpinFailure_stillUploadsAndOverwritesUris() {
        val priorCid = "bafyPriorRoot"
        val newCid = "bafyNewRoot"
        val primary = primaryWithDirectoryProvenance(priorCid)
        val filebase = RecordingFilebaseStorageProvider(
            unpinSucceeds = false,
            uploadDirectoryResultUri = "https://ipfs.filebase.io/ipfs/$newCid",
        )

        assertTrue(enqueueUpload(primary, filebase))

        assertEquals(listOf(priorCid), filebase.unpinCalls)
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(
            listOf(
                Triple(
                    hash,
                    hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX,
                    FilebaseGatewayUris.buildProofsetUri(newCid),
                ),
                Triple(
                    hash,
                    hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX,
                    FilebaseGatewayUris.buildLeafImageUri("bafyMediaLeaf"),
                ),
            ),
            primary.saveTextCalls,
        )
    }

    @Test
    fun uploadFailure_afterUnpin_doesNotOverwriteUrisOrAdvanceStamp() {
        val priorCid = "bafyPriorRoot"
        val proofsetUri = FilebaseGatewayUris.buildProofsetUri(priorCid)
        val primary = primaryWithDirectoryProvenance(priorCid)
        val filebase = RecordingFilebaseStorageProvider(
            uploadDirectorySucceed = false,
            uploadDirectoryResultUri = "https://ipfs.filebase.io/ipfs/bafyShouldNotPersist",
        )

        assertTrue(enqueueUpload(primary, filebase))

        assertEquals(listOf(priorCid), filebase.unpinCalls)
        assertTrue(primary.saveTextCalls.isEmpty())
        assertNull(ProofSetUploader.lastUploadedMembership(hash))
        assertEquals(
            proofsetUri,
            primary.getInputStream(hash, hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX)
                ?.bufferedReader()?.use { it.readText() },
        )
    }

    @Test
    fun imageOnly_noIpfsUri_skipsUnpin() {
        // Hard cutover: only `{hash}.filebase.ipfs.uri` triggers unpin — image alone does not.
        val leafImageUri = FilebaseGatewayUris.buildLeafImageUri("bafyLeafOnly")
        val streams = coreBasenames().associateWith { it.toByteArray() }.toMutableMap()
        streams[hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX] = leafImageUri.toByteArray()
        val primary = primaryWithCore(streams = streams)
        val filebase = RecordingFilebaseStorageProvider()

        assertTrue(enqueueUpload(primary, filebase))

        assertTrue(filebase.unpinCalls.isEmpty())
        assertEquals(1, filebase.uploadDirectoryCalls.size)
    }

    @Test
    fun shareQueuedBehindComposite_reassemblesAfterAcquire_andSkipsWhenStampMatches() {
        val primary = AccumulatingStorageProvider()
        val shareListener = RecordingListener()
        val secondary = object : RecordingFilebaseStorageProvider() {
            override fun uploadDirectory(
                hash: String,
                artifacts: List<DeferredArtifact>,
                mediaBasename: String?,
                listener: StorageListener?,
            ): FilebaseUploadResult? {
                uploadDirectoryCalls.add(
                    hash to artifacts.map { DeferredArtifact.ofBytes(it.identifier, it.readAllBytes(), it.contentType) },
                )
                val leafCid = if (!mediaBasename.isNullOrBlank()) uploadDirectoryMediaLeafCid else null
                if (uploadDirectoryCalls.size == 1) {
                    val parked = enqueueUpload(primary, this, listener = shareListener)
                    assertTrue(parked)
                    assertEquals(1, uploadDirectoryCalls.size)
                    assertTrue(shareListener.successes.isEmpty())
                }
                listener?.saveSuccessful(hash, uploadDirectoryResultUri)
                return FilebaseUploadResult(uploadDirectoryResultUri, leafCid)
            }
        }
        val composite = CompositeStorageProvider(
            primary,
            secondary,
            context,
            deferProofSetUpload = true,
            filebaseConfig = FilebaseConfig(
                accessKey = "ak",
                secretKey = "sk",
                bucketName = "bucket",
                enabled = true,
                ipfsBearerToken = "token",
                autoUpload = true,
                autoIncludeMedia = true,
            ),
        )
        val mediaFile = java.io.File(context.cacheDir, "$hash-media.jpg")
        mediaFile.writeBytes(byteArrayOf(1, 2, 3))
        composite.bindMedia(hash, Uri.fromFile(mediaFile), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        assertTrue(secondary.unpinCalls.isEmpty())
        assertEquals(1, shareListener.successes.size)
        assertEquals(hash, shareListener.successes.single().first)
    }

    @Test
    fun membershipUnchanged_skipDoesNotUnpinOrOverwrite_returnsTrue() {
        val priorCid = "bafyPriorRoot"
        val primary = primaryWithDirectoryProvenance(priorCid)
        val filebase = RecordingFilebaseStorageProvider()
        val media = byteArrayOf(1, 2, 3)

        assertTrue(enqueueUpload(primary, filebase, media = ProofSetMediaSource.ofBytes(media, "image/jpeg")))
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(1, filebase.unpinCalls.size)

        val listener = RecordingListener()
        val skipped = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = ProofSetMediaSource.ofBytes(media, "image/jpeg"),
        )

        assertTrue(skipped)
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(1, filebase.unpinCalls.size)
        assertEquals(1, listener.successes.size)
    }

    @Test
    fun incompleteFirstPass_directCaller_doesNotInvokeSaveFailed() {
        val incomplete = coreBasenames() - "$hash.proof.json"
        val primary = primaryWithCore(proofNames = incomplete)
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        val started = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = ProofSetMediaSource.ofBytes(byteArrayOf(9), "image/jpeg"),
        )

        assertFalse(started)
        // Incomplete returns false for Share not-ready; it must not also invoke saveFailed
        // (that would stack an upload-failed toast on the same attempt).
        assertTrue(listener.failures.isEmpty())
        assertTrue(listener.successes.isEmpty())
        assertTrue(filebase.unpinCalls.isEmpty())
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
        assertTrue(filebase.saveArtifactCalls.isEmpty())
        assertTrue(primary.saveTextCalls.isEmpty())
        assertTrue(primary.getInputStreamCalls.isEmpty())
        assertEquals(0, ProofSetUploader.occupancyCount(hash))
    }

    @Test
    fun parkedShare_waitingBehindFailedUpload_notifiesListenerFailure() {
        // Replaces the old park/drain "parkedShare_drainsToIncomplete_notifiesListenerFailure" test.
        //
        // New Mutex semantics: a second enqueue scheduled while the first holds the Mutex suspends
        // inside its coroutine at mutex.withLock. When the first coroutine's withLock exits
        // (even on failure), the Mutex releases and the second coroutine resumes.
        // If storage became incomplete between the two, the second notifies saveFailed.
        //
        // With Dispatchers.Unconfined (installed by setUp), all coroutines run synchronously:
        // Coroutine2's launch inside uploadDirectory suspends at withLock (mutex held), control
        // returns to Coroutine1, which finishes and releases the lock, then Coroutine2 resumes
        // inline — so all state is observable immediately after enqueueProofSetUpload returns.
        val primary = AccumulatingStorageProvider()
        for (name in coreBasenames()) {
            primary.saveBytes(hash, name, name.toByteArray(), null)
        }
        val parkedListener = RecordingListener()
        val filebase = object : RecordingFilebaseStorageProvider(uploadDirectorySucceed = false) {
            override fun uploadDirectory(
                hash: String,
                artifacts: List<DeferredArtifact>,
                mediaBasename: String?,
                listener: StorageListener?,
            ): FilebaseUploadResult? {
                uploadDirectoryCalls.add(
                    hash to artifacts.map { DeferredArtifact.ofBytes(it.identifier, it.readAllBytes(), it.contentType) },
                )
                // While Coroutine1 holds the Mutex, schedule a second enqueue (Coroutine2).
                // With Dispatchers.Unconfined, Coroutine2 launches immediately but suspends at
                // mutex.withLock (Mutex held by Coroutine1). enqueueProofSetUpload returns true.
                val parked = enqueueUpload(primary, this, listener = parkedListener)
                assertTrue(parked)
                // Coroutine2 is suspended — parkedListener has NOT fired yet.
                assertTrue(parkedListener.failures.isEmpty())
                // Simulate storage becoming incomplete before Coroutine2 acquires the Mutex.
                primary.removeMemberForTesting("$hash.proof.json")
                listener?.saveFailed(RuntimeException("uploadDirectory failed"))
                return null
            }
        }

        // Coroutine1: acquires Mutex, uploadDirectory runs (launches Coroutine2 which suspends),
        // fails → result null → withLock exits → Mutex released → Coroutine2 resumes inline
        // → post-acquire reassemble finds incomplete → parkedListener.saveFailed fires.
        // All sync under Dispatchers.Unconfined, so all state is observable after this returns.
        assertTrue(enqueueUpload(primary, filebase))

        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(1, parkedListener.failures.size)
        assertTrue(parkedListener.successes.isEmpty())
        assertNull(ProofSetUploader.lastUploadedMembership(hash))
    }

    @Test
    fun leafCidDeliveredViaReturnValue_notLeakedAcrossHashes() {
        val cid = "bafyDirNoLeaf"
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider(
            uploadDirectoryResultUri = FilebaseGatewayUris.buildProofsetUri(cid),
            uploadDirectoryMediaLeafCid = null,
        )

        assertTrue(enqueueUpload(primary, filebase))

        val imageUri = primary.saveTextCalls
            .single { it.second == hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX }.third
        assertEquals(FilebaseGatewayUris.buildImageUriUnderDirectory(cid, "$hash.jpg"), imageUri)
    }

    @Test
    fun stampSkip_coalescesImageSidecarWhenIpfsAbsent() {
        val primary = AccumulatingStorageProvider()
        for (name in coreBasenames()) {
            primary.saveBytes(hash, name, name.toByteArray(), null)
        }
        val filebase = RecordingFilebaseStorageProvider()
        val media = byteArrayOf(1, 2, 3)
        val mediaBasename = "$hash.jpg"
        val expectedImageUri = "s3://bucket/$hash/$mediaBasename"

        assertTrue(
            enqueueUpload(
                primary,
                filebase,
                media = ProofSetMediaSource.ofBytes(media, "image/jpeg"),
                mode = UploadMode.S3_MEMBERS,
            ),
        )
        assertNull(
            primary.getInputStream(hash, hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX),
        )
        val imageSidecarUri = primary.getInputStream(
            hash,
            hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX,
        )?.bufferedReader()?.use { it.readText() }
        assertEquals(expectedImageUri, imageSidecarUri)

        val listener = RecordingListener()
        val skipped = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = ProofSetMediaSource.ofBytes(media, "image/jpeg"),
            mode = UploadMode.S3_MEMBERS,
        )

        assertTrue(skipped)
        assertEquals(coreBasenames().size + 1, filebase.saveArtifactCalls.size)
        assertEquals(1, listener.successes.size)
        assertEquals(hash to expectedImageUri, listener.successes.single())
    }

    @Test
    fun firstInsertionRace_computeIfAbsent_serializesUploads() {
        // Regression test for getOrPut atomicity bug (would fail with getOrPut, passes with
        // computeIfAbsent). Two coroutines race to insert the very first Mutex for a hash that
        // has NEVER been seen before. With getOrPut (non-atomic), both may construct separate
        // Mutex instances and bypass mutual exclusion. With computeIfAbsent, exactly one Mutex
        // is installed and both calls are serialized.
        //
        // Design: N threads simultaneously call enqueueProofSetUpload for the same fresh hash
        // (no prior entry in mutexByHash). A slow fake uploadDirectory uses an AtomicInteger to
        // track concurrent-entry count; the peak must never exceed 1 if serialization holds.
        // CyclicBarrier ensures all threads reach the enqueue call at the same instant.
        ProofSetUploader.clearMapsForTesting()  // real Dispatchers.IO for genuine concurrency

        val freshHash = "firstInsertionRaceHash_neverSeenBefore"
        val primary = AccumulatingStorageProvider()
        val coreNames = ProofSetMembershipPolicy.requiredCoreBasenames(freshHash)
        for (name in coreNames) {
            primary.saveBytes(freshHash, name, name.toByteArray(), null)
        }

        val concurrentEntries = AtomicInteger(0)
        var peakConcurrentEntries = 0
        val peakLock = Any()

        val N = 4
        val allCompletedLatch = CountDownLatch(N)
        val barrier = CyclicBarrier(N)

        val filebase = object : RecordingFilebaseStorageProvider() {
            override fun uploadDirectory(
                hash: String,
                artifacts: List<DeferredArtifact>,
                mediaBasename: String?,
                listener: StorageListener?,
            ): FilebaseUploadResult? {
                val current = concurrentEntries.incrementAndGet()
                synchronized(peakLock) {
                    if (current > peakConcurrentEntries) peakConcurrentEntries = current
                }
                // Simulate a slow upload so concurrent entries would overlap if not serialized.
                Thread.sleep(80)
                concurrentEntries.decrementAndGet()
                uploadDirectoryCalls.add(
                    hash to artifacts.map { DeferredArtifact.ofBytes(it.identifier, it.readAllBytes(), it.contentType) },
                )
                listener?.saveSuccessful(hash, uploadDirectoryResultUri)
                return FilebaseUploadResult(uploadDirectoryResultUri, uploadDirectoryMediaLeafCid)
            }
        }

        val threads = (1..N).map {
            Thread {
                barrier.await(5, TimeUnit.SECONDS)  // all threads start at once
                enqueueUpload(
                    primary,
                    filebase,
                    listener = object : StorageListener {
                        override fun saveSuccessful(h: String?, u: String?) { allCompletedLatch.countDown() }
                        override fun saveFailed(e: Exception?) { allCompletedLatch.countDown() }
                    },
                    hash = freshHash,
                )
            }
        }
        threads.forEach { it.start() }

        assertTrue("All $N listeners should fire within 15s", allCompletedLatch.await(15, TimeUnit.SECONDS))

        // If two coroutines ever held different Mutex instances simultaneously, concurrentEntries
        // would have exceeded 1. This assertion is the key serialization guarantee.
        assertEquals(
            "Concurrent upload entry count must never exceed 1 (serialization violated!)",
            1,
            peakConcurrentEntries,
        )

        // Restore Unconfined for subsequent tests.
        ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)
    }

    @Test
    fun s3Mode_neverUnpins() {
        val priorCid = "bafyPriorRoot"
        val primary = primaryWithDirectoryProvenance(priorCid, mediaBasename = "$hash.png")
        val filebase = RecordingFilebaseStorageProvider()

        assertTrue(
            enqueueUpload(
                primary,
                filebase,
                media = ProofSetMediaSource.ofBytes(byteArrayOf(4, 5, 6), "image/png"),
                mode = UploadMode.S3_MEMBERS,
            ),
        )

        assertTrue(filebase.unpinCalls.isEmpty())
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
        assertEquals(coreBasenames().size + 1, filebase.saveArtifactCalls.size)
    }

    @Test
    fun enqueue_acceptsOnCompleteness_saveFailedWhenAssembleUnreadableAfterStart() {
        // Completeness sees all required names onDisk + mediaOk, but one stream is unreadable.
        val missingReadable = "$hash.proof.csv"
        val streams = coreBasenames().associateWith { it.toByteArray() }.toMutableMap()
        streams.remove(missingReadable)
        val primary = RecordingStorageProvider(
            proofSetUris = ArrayList(coreBasenames().map { Uri.parse("file:///proof/$it") }),
            streams = streams,
        )
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        val started = enqueueUpload(primary, filebase, listener = listener)

        assertTrue(started)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.successes.isEmpty())
        assertNull(ProofSetUploader.lastUploadedMembership(hash))
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
    }

    @Test
    fun enqueue_stampMatch_unreadableMembers_saveFailedNotSuccess() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        assertTrue(enqueueUpload(primary, filebase))
        val priorStamp = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(priorStamp != null && priorStamp.basenames.isNotEmpty())

        primary.unreadableIdentifiers.add(coreBasenames().first())
        val listener = RecordingListener()
        val started = enqueueUpload(primary, filebase, listener = listener)

        assertTrue(started)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.successes.isEmpty())
        assertEquals(priorStamp, ProofSetUploader.lastUploadedMembership(hash))
    }

    @Test
    fun stampSkip_includeMedia_mediaNoLongerResolves_saveFailedNotSuccess() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        assertTrue(enqueueUpload(primary, filebase))
        val priorStamp = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(priorStamp != null && priorStamp.basenames.isNotEmpty())

        // Media resolves for the pre-enqueue gate, then disappears before the coroutine takes
        // the lock — the post-acquire re-resolve must fail closed.
        val mediaFile = java.io.File(context.cacheDir, "$hash-vanishing-media.jpg")
        mediaFile.writeBytes(byteArrayOf(1, 2, 3))
        var resolveCalls = 0
        val listener = RecordingListener()
        val started = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = ProofSetMediaSource.fromUriProvider(context) {
                resolveCalls++
                if (resolveCalls == 1) {
                    Uri.fromFile(mediaFile) to "image/jpeg"
                } else {
                    Uri.parse("content://missing/media") to "image/jpeg"
                }
            },
        )

        assertTrue(started)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.successes.isEmpty())
        assertEquals(priorStamp, ProofSetUploader.lastUploadedMembership(hash))
    }

    @Test
    fun sidecarsOnly_assembleOmitsMediaLeaf_andAdvancesStampWithoutMediaBasename() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()
        val mediaBasename = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, "image/jpeg")

        val started = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = null,
            inclusion = MediaInclusion.SIDECARS_ONLY,
        )

        assertTrue(started)
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        val ids = filebase.uploadDirectoryCalls.single().second.map { it.identifier }
        assertFalse(mediaBasename in ids)
        assertTrue(coreBasenames().all { it in ids })
        assertEquals(
            listOf(
                Triple(
                    hash,
                    hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX,
                    filebase.uploadDirectoryResultUri,
                ),
            ),
            primary.saveTextCalls,
        )

        val stamp = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(stamp != null)
        assertEquals(MediaInclusion.SIDECARS_ONLY, stamp!!.mediaInclusion)
        assertEquals(FilebaseConfig.UploadMode.IPFS_DIRECTORY, stamp.uploadMode)
        assertFalse(mediaBasename in stamp.basenames)
        assertTrue(coreBasenames().all { it in stamp.basenames })
        assertEquals(1, listener.successes.size)
    }

    @Test
    fun stampSkip_sidecarsOnly_doesNotFailClosedWhenMediaMissing() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()

        assertTrue(enqueueUpload(primary, filebase, media = null, inclusion = MediaInclusion.SIDECARS_ONLY))
        assertEquals(1, filebase.uploadDirectoryCalls.size)

        val listener = RecordingListener()
        val skipped = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = null,
            inclusion = MediaInclusion.SIDECARS_ONLY,
        )

        assertTrue(skipped)
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(1, listener.successes.size)
        assertTrue(listener.failures.isEmpty())
    }

    @Test
    fun stampSkip_sidecarsOnly_unreadableCores_saveFailedNotSuccess() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        assertTrue(enqueueUpload(primary, filebase, media = null, inclusion = MediaInclusion.SIDECARS_ONLY))
        val priorStamp = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(priorStamp != null && priorStamp.basenames.isNotEmpty())

        primary.unreadableIdentifiers.add(coreBasenames().first())
        val listener = RecordingListener()
        val started = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = null,
            inclusion = MediaInclusion.SIDECARS_ONLY,
        )

        assertTrue(started)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.successes.isEmpty())
        assertEquals(priorStamp, ProofSetUploader.lastUploadedMembership(hash))
    }

    @Test
    fun includeMedia_thenSidecarsOnly_doesNotStampMatch() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()

        assertTrue(enqueueUpload(primary, filebase))
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        val includeStamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertEquals(MediaInclusion.INCLUDE_MEDIA, includeStamp.mediaInclusion)

        val listener = RecordingListener()
        assertTrue(
            enqueueUpload(
                primary,
                filebase,
                listener = listener,
                media = null,
                inclusion = MediaInclusion.SIDECARS_ONLY,
            ),
        )

        assertEquals(2, filebase.uploadDirectoryCalls.size)
        val sidecarsStamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertEquals(MediaInclusion.SIDECARS_ONLY, sidecarsStamp.mediaInclusion)
        assertNotEquals(includeStamp, sidecarsStamp)
        assertEquals(1, listener.successes.size)
    }

    @Test
    fun s3Members_sidecarsOnly_successAdvancesStamp_withZeroUriSidecars() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        val started = enqueueUpload(
            primary,
            filebase,
            listener = listener,
            media = null,
            mode = UploadMode.S3_MEMBERS,
            inclusion = MediaInclusion.SIDECARS_ONLY,
        )

        assertTrue(started)
        assertEquals(coreBasenames().size, filebase.saveArtifactCalls.size)
        assertFalse(filebase.saveArtifactCalls.any { it.second == "$hash.jpg" })
        assertTrue(primary.saveTextCalls.isEmpty())

        val stamp = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(stamp != null)
        assertEquals(MediaInclusion.SIDECARS_ONLY, stamp!!.mediaInclusion)
        assertEquals(FilebaseConfig.UploadMode.S3_MEMBERS, stamp.uploadMode)
        assertFalse("$hash.jpg" in stamp.basenames)
        assertEquals(1, listener.successes.size)
    }

    @Test
    fun stamp_s3SidecarsOnly_thenSwitchIpfs_sameBasenames_doesNotSkip_writesProofsetUri() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        assertTrue(
            enqueueUpload(
                primary,
                filebase,
                listener = listener,
                media = null,
                mode = UploadMode.S3_MEMBERS,
                inclusion = MediaInclusion.SIDECARS_ONLY,
            ),
        )
        assertEquals(coreBasenames().size, filebase.saveArtifactCalls.size)
        assertTrue(primary.saveTextCalls.isEmpty())
        assertEquals(1, listener.successes.size)

        val s3Stamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertEquals(FilebaseConfig.UploadMode.S3_MEMBERS, s3Stamp.uploadMode)
        assertEquals(MediaInclusion.SIDECARS_ONLY, s3Stamp.mediaInclusion)

        val switchListener = RecordingListener()
        assertTrue(
            enqueueUpload(
                primary,
                filebase,
                listener = switchListener,
                media = null,
                inclusion = MediaInclusion.SIDECARS_ONLY,
            ),
        )

        assertEquals(1, filebase.uploadDirectoryCalls.size)
        val cid = FilebaseGatewayUris.parseGatewayRootCid(filebase.uploadDirectoryResultUri)!!
        assertEquals(
            listOf(
                Triple(
                    hash,
                    hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX,
                    FilebaseGatewayUris.buildProofsetUri(cid),
                ),
            ),
            primary.saveTextCalls,
        )
        assertFalse(
            primary.saveTextCalls.any {
                it.second == hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX
            },
        )
        val ipfsStamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertEquals(FilebaseConfig.UploadMode.IPFS_DIRECTORY, ipfsStamp.uploadMode)
        assertEquals(s3Stamp.basenames, ipfsStamp.basenames)
        assertNotEquals(s3Stamp, ipfsStamp)
        assertEquals(1, switchListener.successes.size)
    }

    /**
     * Large-upload reliability: Filebase timeout must reach the Share/Upload listener, not stall
     * as an uncompleted enqueue.
     */
    @Test
    fun ipfsUpload_socketTimeout_notifiesSaveFailed() {
        val primary = primaryWithCore()
        val filebase = object : RecordingFilebaseStorageProvider(uploadDirectorySucceed = false) {
            override fun uploadDirectory(
                hash: String,
                artifacts: List<DeferredArtifact>,
                mediaBasename: String?,
                listener: StorageListener?,
            ): FilebaseUploadResult? {
                uploadDirectoryCalls.add(hash to artifacts)
                val error = SocketTimeoutException("timeout")
                listener?.saveFailed(error)
                return null
            }
        }
        val listener = RecordingListener()

        assertTrue(enqueueUpload(primary, filebase, listener = listener))

        assertEquals(1, listener.failures.size)
        assertTrue(listener.failures.single() is SocketTimeoutException)
        assertTrue(listener.successes.isEmpty())
    }

    @Test
    fun occupancy_inFlightUntilStrategyReturns() {
        val entered = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val filebase = LatchedFilebaseStorageProvider(hold, entered)
        ProofSetUploader.clearMapsForTesting(Dispatchers.IO)
        val primary = primaryWithCore()

        try {
            assertTrue(enqueueUpload(primary, filebase))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(1, ProofSetUploader.occupancyCount(hash))
            // A subscriber that arrives after the upload started still sees it in flight.
            assertEquals(1, runBlocking { ProofSetUploader.occupancyFor(hash).first() })
            hold.countDown()
            assertTrue("occupancy should drop when Strategy returns", awaitOccupancyZero(listOf(hash)))
        } finally {
            hold.countDown()
            ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)
        }
    }

    @Test
    fun occupancy_sameHashOverlap_inFlightCountMatchesAcceptedJobs() {
        // Same-hash mutex waiters must not
        // report occupancy == accepted-job count. The old assertion (occupancy==3
        // for 3 accepted jobs) is deleted. Occupancy 0 is waiter-idle, not the
        // overlapping-job join — wait allCompletedLatch(3) before polling 0.
        val entered = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val allCompletedLatch = CountDownLatch(3)
        val filebase = LatchedFilebaseStorageProvider(hold, entered)
        ProofSetUploader.clearMapsForTesting(Dispatchers.IO)
        val primary = primaryWithCore()

        fun enqueue(): Boolean = enqueueUpload(primary, filebase, listener = CountingListener(allCompletedLatch))

        assertTrue(enqueue())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(enqueue())
        assertTrue(enqueue())
        assertEquals(1, ProofSetUploader.occupancyCount(hash))

        hold.countDown()
        assertTrue(
            "all 3 listeners should fire within 10s (jobs 2–3 may stamp-skip; occupancy 0 is not the join)",
            allCompletedLatch.await(10, TimeUnit.SECONDS),
        )
        assertEquals("waiters must stamp-skip, not upload again", 1, filebase.uploadDirectoryCalls.size)
        assertTrue(
            "occupancy 0 after all overlapping same-hash jobs complete (not waiter-idle mid-overlap)",
            awaitOccupancyZero(listOf(hash)),
        )
        ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)
    }

    @Test
    fun occupancy_fourthDistinctHash_staysZeroUntilSlotFrees() {
        val hashes = listOf("slotcap1", "slotcap2", "slotcap3", "slotcap4")
        val entered = CountDownLatch(3)
        val allEntered = CountDownLatch(4)
        val hold = CountDownLatch(1)
        val filebase = LatchedFilebaseStorageProvider(hold, entered, allEntered)

        fun makePrimary(h: String): RecordingStorageProvider {
            val names = ProofSetMembershipPolicy.requiredCoreBasenames(h)
            return RecordingStorageProvider(
                proofSetUris = ArrayList(names.map { Uri.parse("file:///proof/$it") }),
                streams = names.associateWith { it.toByteArray() },
            )
        }

        ProofSetUploader.clearMapsForTesting(Dispatchers.IO)
        val primaries = hashes.associateWith { makePrimary(it) }

        val accepted = hashes.map { h ->
            enqueueUpload(
                primaries.getValue(h),
                filebase,
                media = ProofSetMediaSource.ofBytes(byteArrayOf(1), "image/jpeg"),
                hash = h,
            )
        }
        assertTrue(accepted.all { it })
        assertTrue("first three distinct hashes should enter Strategy", entered.await(10, TimeUnit.SECONDS))

        val inSlot = hashes.filter { ProofSetUploader.occupancyCount(it) == 1 }
        val idle = hashes.filter { ProofSetUploader.occupancyCount(it) == 0 }
        assertEquals(3, inSlot.size)
        assertEquals(1, idle.size)
        assertEquals(filebase.enteredHashes.toSet(), inSlot.toSet())
        assertEquals(0, ProofSetUploader.occupancyCount(idle.single()))
        hashes.forEach { h ->
            val n = ProofSetUploader.occupancyCount(h)
            assertTrue("hash $h occupancy=$n must be 0 or 1 (in-slot, not accepted-job count)", n == 0 || n == 1)
        }

        hold.countDown()
        assertTrue("all four strategies should run after a slot frees", allEntered.await(10, TimeUnit.SECONDS))
        assertTrue("final occupancy must be 0 for all four hashes", awaitOccupancyZero(hashes, timeoutSeconds = 10))
        ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)
    }
}

open class RecordingStorageProvider(
    private val proofSetUris: ArrayList<Uri> = arrayListOf(),
    private val streams: Map<String, ByteArray> = emptyMap(),
) : StorageProvider {
    val getInputStreamCalls = mutableListOf<Pair<String, String>>()
    val saveTextCalls = mutableListOf<Triple<String, String, String>>()
    /** When set, [getInputStream] returns null for these identifiers (even if present in streams). */
    val unreadableIdentifiers = mutableSetOf<String>()

    override fun saveStream(hash: String?, identifier: String?, stream: InputStream?, listener: StorageListener?) {
        throw UnsupportedOperationException()
    }

    override fun saveBytes(hash: String?, identifier: String?, data: ByteArray?, listener: StorageListener?) {
        throw UnsupportedOperationException()
    }

    override fun saveText(hash: String?, identifier: String?, data: String?, listener: StorageListener?) {
        saveTextCalls.add(Triple(hash!!, identifier!!, data!!))
        listener?.saveSuccessful(hash, "file:///$identifier")
    }

    override fun replaceText(hash: String?, identifier: String?, data: String?, listener: StorageListener?) {
        // Record alongside saveText so URI-sidecar assertions keep working.
        saveTextCalls.add(Triple(hash!!, identifier!!, data!!))
        listener?.saveSuccessful(hash, "file:///$identifier")
    }

    override fun getInputStream(hash: String?, identifier: String?): InputStream? {
        getInputStreamCalls.add(hash!! to identifier!!)
        if (identifier in unreadableIdentifiers) return null
        val bytes = streams[identifier] ?: return null
        return ByteArrayInputStream(bytes)
    }

    override fun proofExists(hash: String?): Boolean = false

    override fun proofIdentifierExists(hash: String?, identifier: String?): Boolean =
        streams.containsKey(identifier)

    override fun getProofSet(hash: String?): ArrayList<Uri> = ArrayList(proofSetUris)

    override fun getProofItem(uri: Uri?): InputStream? = null
}

/**
 * Test-only: materialize an artifact's source so assertions can compare content.
 *
 * Production code must never do this for the media leaf — see [DeferredArtifact].
 */
internal fun DeferredArtifact.readAllBytes(): ByteArray = openStream().use { it.readBytes() }

/** Filebase test double that records unpin + uploadDirectory / saveArtifact without network. */
open class RecordingFilebaseStorageProvider(
    var unpinSucceeds: Boolean = true,
    var uploadDirectorySucceed: Boolean = true,
    var uploadDirectoryResultUri: String = "https://ipfs.filebase.io/ipfs/bafyRoot",
    /** When set, simulates NDJSON media-leaf CID for leaf-form image.uri. */
    var uploadDirectoryMediaLeafCid: String? = "bafyMediaLeaf",
) : FilebaseStorageProvider(
    accessKey = "",
    secretKey = "",
    bucketName = "",
    ipfsBearerToken = "test-token",
) {
    val unpinCalls = mutableListOf<String>()
    val uploadDirectoryCalls = mutableListOf<Pair<String, List<DeferredArtifact>>>()
    val saveArtifactCalls = mutableListOf<Triple<String, String, ByteArray>>()

    override fun unpinIpfsCid(cid: String): Boolean {
        unpinCalls.add(cid)
        return unpinSucceeds
    }

    override fun uploadDirectory(
        hash: String,
        artifacts: List<DeferredArtifact>,
        mediaBasename: String?,
        listener: StorageListener?,
    ): FilebaseUploadResult? {
        uploadDirectoryCalls.add(
            hash to artifacts.map { DeferredArtifact.ofBytes(it.identifier, it.readAllBytes(), it.contentType) },
        )
        val leafCid = if (!mediaBasename.isNullOrBlank()) uploadDirectoryMediaLeafCid else null
        return if (uploadDirectorySucceed) {
            listener?.saveSuccessful(hash, uploadDirectoryResultUri)
            FilebaseUploadResult(uploadDirectoryResultUri, leafCid)
        } else {
            listener?.saveFailed(RuntimeException("uploadDirectory failed"))
            null
        }
    }

    override fun saveArtifact(hash: String, artifact: DeferredArtifact, listener: StorageListener?) {
        saveArtifactCalls.add(Triple(hash, artifact.identifier, artifact.readAllBytes()))
        listener?.saveSuccessful(hash, "s3://bucket/$hash/${artifact.identifier}")
    }
}

/** Holds every uploadDirectory call on [hold] after counting down each [entered] latch. */
private class LatchedFilebaseStorageProvider(
    private val hold: CountDownLatch,
    private vararg val entered: CountDownLatch,
) : RecordingFilebaseStorageProvider() {
    val enteredHashes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun uploadDirectory(
        hash: String,
        artifacts: List<DeferredArtifact>,
        mediaBasename: String?,
        listener: StorageListener?,
    ): FilebaseUploadResult? {
        enteredHashes.add(hash)
        entered.forEach { it.countDown() }
        check(hold.await(10, TimeUnit.SECONDS))
        return super.uploadDirectory(hash, artifacts, mediaBasename, listener)
    }
}

private class CountingListener(private val completed: CountDownLatch) : StorageListener {
    override fun saveSuccessful(hash: String?, uri: String?) = completed.countDown()

    override fun saveFailed(exception: Exception?) = completed.countDown()
}

private class RecordingListener : StorageListener {
    val successes = mutableListOf<Pair<String?, String?>>()
    val failures = mutableListOf<Exception?>()

    override fun saveSuccessful(hash: String?, uri: String?) {
        successes.add(hash to uri)
    }

    override fun saveFailed(exception: Exception?) {
        failures.add(exception)
    }
}
