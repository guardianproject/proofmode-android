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
import java.io.InputStream
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import org.witness.proofmode.storage.AccumulatingStorageProvider
import org.witness.proofmode.storage.CompositeStorageProvider
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.filebase.FilebaseConfig
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

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(media, "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
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
    }

    @Test
    fun ipfsSuccess_writesIpfsAndImageUri() {
        val gatewayUri = "https://ipfs.filebase.io/ipfs/bafyTestRoot"
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider(uploadDirectoryResultUri = gatewayUri)
        val cid = FilebaseGatewayUris.parseGatewayRootCid(gatewayUri)!!

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )

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
        assertFalse(
            primary.saveTextCalls.any {
                it.first == hash &&
                    it.second == hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX &&
                    it.third.startsWith("s3://")
            },
        )
    }

    @Test
    fun incompleteFirstPass_returnsFalse_noUpload() {
        val incomplete = coreBasenames() - "$hash.proof.json"
        val primary = primaryWithCore(proofNames = incomplete)
        val filebase = RecordingFilebaseStorageProvider()

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(9), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        )

        assertFalse(started)
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
        assertTrue(filebase.saveArtifactCalls.isEmpty())
        assertTrue(primary.saveTextCalls.isEmpty())
    }

    @Test
    fun incompleteWhenMediaMissing_returnsFalse_noUpload() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()

        assertFalse(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )
        assertFalse(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(), "image/jpeg"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            null,
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

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(media, "image/png"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
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

    @Test
    fun s3MembersSuccess_writesImageUriOnly_fromMediaMember() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val mediaBasename = "$hash.mp4"
        val mediaUri = "s3://bucket/$hash/$mediaBasename"

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(7, 8, 9), "video/mp4"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )

        assertEquals(
            listOf(Triple(hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX, mediaUri)),
            primary.saveTextCalls,
        )
        assertFalse(
            primary.saveTextCalls.any { it.second == hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX },
        )
    }

    @Test
    fun neverPartialWhenIncomplete_noSaveBytesOrUploadDirectory() {
        val incomplete = coreBasenames() - "$hash.asc"
        val primary = primaryWithCore(proofNames = incomplete)
        val filebase = RecordingFilebaseStorageProvider()

        assertFalse(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1), "video/mp4"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )
        assertTrue(filebase.saveArtifactCalls.isEmpty())
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
        assertTrue(primary.getInputStreamCalls.isEmpty())
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
    fun hasIpfsDirectoryUri_trueWhenIpfsSidecarPresent() {
        val priorCid = "bafyPrior"
        val primary = primaryWithDirectoryProvenance(priorCid)
        assertTrue(FilebaseSidecarContract.hasIpfsDirectoryUri(primary, hash))
        assertEquals(priorCid, FilebaseSidecarContract.readPriorDirectoryRootCid(primary, hash))
    }

    @Test
    fun hasIpfsDirectoryUri_trueWhenOnlyStreamPresent() {
        val priorCid = "bafyPrior"
        val proofsetUri = FilebaseGatewayUris.buildProofsetUri(priorCid)
        val imageUri = FilebaseGatewayUris.buildImageUriUnderDirectory(priorCid, "$hash.jpg")
        val streams = coreBasenames().associateWith { it.toByteArray() }.toMutableMap()
        streams[hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX] = proofsetUri.toByteArray()
        streams[hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX] = imageUri.toByteArray()
        val primary = primaryWithCore(streams = streams)
        assertTrue(FilebaseSidecarContract.hasIpfsDirectoryUri(primary, hash))
        assertEquals(priorCid, FilebaseSidecarContract.readPriorDirectoryRootCid(primary, hash))
    }

    @Test
    fun ipfsReupload_unpinsPriorDirectoryCidOnce_thenUploads() {
        val priorCid = "bafyPriorRoot"
        val primary = primaryWithDirectoryProvenance(priorCid)
        val filebase = RecordingFilebaseStorageProvider(
            uploadDirectoryResultUri = "https://ipfs.filebase.io/ipfs/bafyNewRoot",
        )
        val media = byteArrayOf(1, 2, 3)

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(media, "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
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
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
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

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )

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

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )

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
    fun ipfsUpload_persistSkipped_saveFailed_doesNotAdvanceStamp() {
        // arrange: RecordingFilebase returns FilebaseUploadResult with unparseable directoryUri
        // so persistIpfsDirectorySuccess cannot parse a root CID.
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider(
            uploadDirectoryResultUri = "not-a-gateway-uri",
        )
        val listener = RecordingListener()

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        )

        assertTrue(started)
        assertNull(ProofSetUploader.lastUploadedMembership(hash))
        assertNull(
            primary.getInputStream(hash, hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX),
        )
        assertEquals(0, listener.successes.size) // MUST NOT treat transfer-only as success
        assertEquals(1, listener.failures.size) // required — not optional
    }

    @Test
    fun firstUpload_skipsUnpin_whenNoProvenance() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )

        assertTrue(filebase.unpinCalls.isEmpty())
        assertEquals(1, filebase.uploadDirectoryCalls.size)
    }

    @Test
    fun imageOnly_noIpfsUri_skipsUnpin() {
        // Hard cutover: only `{hash}.filebase.ipfs.uri` triggers unpin — image alone does not.
        val leafImageUri = FilebaseGatewayUris.buildLeafImageUri("bafyLeafOnly")
        val streams = coreBasenames().associateWith { it.toByteArray() }.toMutableMap()
        streams[hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX] = leafImageUri.toByteArray()
        val primary = primaryWithCore(streams = streams)
        val filebase = RecordingFilebaseStorageProvider()

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )

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
                    val parked = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            this,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            shareListener,
        )
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
    fun compositeQueuedBehindShare_samePostAcquireSkip() {
        val primary = AccumulatingStorageProvider()
        for (name in coreBasenames()) {
            primary.saveBytes(hash, name, name.toByteArray(), null)
        }
        val mediaFile = java.io.File(context.cacheDir, "$hash-direct-media.jpg")
        mediaFile.writeBytes(byteArrayOf(1, 2, 3))
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
                    val composite = CompositeStorageProvider(
                        primary,
                        this,
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
                    val compositeMedia = java.io.File(context.cacheDir, "$hash-share-media.jpg")
                    compositeMedia.writeBytes(byteArrayOf(1, 2, 3))
                    composite.bindMedia(hash, Uri.fromFile(compositeMedia), "image/jpeg")
                    for (name in coreBasenames()) {
                        composite.saveBytes(hash, name, name.toByteArray(), null)
                    }
                    assertEquals(1, uploadDirectoryCalls.size)
                }
                listener?.saveSuccessful(hash, uploadDirectoryResultUri)
                return FilebaseUploadResult(uploadDirectoryResultUri, leafCid)
            }
        }
        ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            secondary,
            ProofSetMediaSource.fromUriProvider(context) { Uri.fromFile(mediaFile) to "image/jpeg" },
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        )

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        assertTrue(secondary.unpinCalls.isEmpty())
    }

    @Test
    fun membershipUnchanged_skipDoesNotUnpinOrOverwrite_returnsTrue() {
        val priorCid = "bafyPriorRoot"
        val primary = primaryWithDirectoryProvenance(priorCid)
        val filebase = RecordingFilebaseStorageProvider()
        val media = byteArrayOf(1, 2, 3)

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(media, "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(1, filebase.unpinCalls.size)

        val listener = RecordingListener()
        val skipped = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(media, "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        )

        assertTrue(skipped)
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(1, filebase.unpinCalls.size)
        assertEquals(1, listener.successes.size)
    }

    @Test
    fun incompleteFirstPass_beforeUnpin_releasesInFlight_returnsFalse() {
        val incomplete = coreBasenames() - "$hash.proof.json"
        val primary = primaryWithCore(proofNames = incomplete)
        val filebase = RecordingFilebaseStorageProvider()
        val directListener = RecordingListener()

        val leader = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(9), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        )
        assertFalse(leader)

        val direct = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(9), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            directListener,
        )
        assertFalse(direct)

        assertTrue(filebase.unpinCalls.isEmpty())
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
        // Incomplete returns false for Share not-ready; must not also invoke saveFailed
        // (that would stack upload-failed toast on the same attempt).
        assertTrue(directListener.failures.isEmpty())
        assertTrue(directListener.successes.isEmpty())
    }

    @Test
    fun incompleteFirstPass_directCaller_doesNotInvokeSaveFailed() {
        val incomplete = coreBasenames() - "$hash.proof.json"
        val primary = primaryWithCore(proofNames = incomplete)
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(9), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        )

        assertFalse(started)
        assertTrue(listener.failures.isEmpty())
        assertTrue(listener.successes.isEmpty())
        assertTrue(filebase.uploadDirectoryCalls.isEmpty())
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
                val parked = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            this,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            parkedListener,
        )
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
        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )

        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(1, parkedListener.failures.size)
        assertTrue(parkedListener.successes.isEmpty())
        assertNull(ProofSetUploader.lastUploadedMembership(hash))
    }

    @Test
    fun leafCidDeliveredViaReturnValue_notLeakedAcrossHashes() {
        val hashA = "aaaa111abc"
        val hashB = "bbbb222def"
        val leafCidA = "bafyLeafCidForHashA"

        fun makePrimary(h: String): RecordingStorageProvider {
            val names = ProofSetMembershipPolicy.requiredCoreBasenames(h)
            return RecordingStorageProvider(
                proofSetUris = ArrayList(names.map { Uri.parse("file:///proof/$it") }),
                streams = names.associateWith { it.toByteArray() },
            )
        }

        val primaryA = makePrimary(hashA)
        val primaryB = makePrimary(hashB)

        // A fake adapter that returns FilebaseUploadResult synchronously.
        // It does NOT write any shared field — leaf CID delivered ONLY via return value.
        val fakeAdapter = object : FilebaseStorageProvider(
            accessKey = "", secretKey = "", bucketName = "", ipfsBearerToken = "tok",
        ) {
            override fun uploadDirectory(
                hash: String,
                artifacts: List<DeferredArtifact>,
                mediaBasename: String?,
                listener: StorageListener?,
            ): FilebaseUploadResult? {
                val dirUri = "https://ipfs.filebase.io/ipfs/bafyDir-$hash"
                val leafCid = if (hash == hashA) leafCidA else null
                listener?.saveSuccessful(hash, dirUri)
                return FilebaseUploadResult(dirUri, leafCid)
            }
        }

        ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)

        // Upload hash A — should write leafCidA into A's image.uri
        ProofSetUploader.enqueueProofSetUpload(
            context,
            hashA,
            primaryA,
            fakeAdapter,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        )

        // Upload hash B — should NOT leak leafCidA; adapter returns null leaf for B
        ProofSetUploader.enqueueProofSetUpload(
            context,
            hashB,
            primaryB,
            fakeAdapter,
            ProofSetMediaSource.ofBytes(byteArrayOf(4, 5, 6), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        )

        // Hash A image.uri must be leaf-form (using its own leaf CID)
        val aImageUri = primaryA.saveTextCalls
            .firstOrNull { it.second == hashA + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX }?.third
        assertEquals(FilebaseGatewayUris.buildLeafImageUri(leafCidA), aImageUri)

        // Hash B image.uri must NOT use hashA's leaf CID — B has no leaf, uses path-under-directory
        val bImageUri = primaryB.saveTextCalls
            .firstOrNull { it.second == hashB + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX }?.third
        val bDirCid = FilebaseGatewayUris.parseGatewayRootCid("https://ipfs.filebase.io/ipfs/bafyDir-$hashB")!!
        val expectedBImageUri = FilebaseGatewayUris.buildImageUriUnderDirectory(bDirCid, "$hashB.jpg")
        assertEquals(expectedBImageUri, bImageUri)
        assertNotEquals(FilebaseGatewayUris.buildLeafImageUri(leafCidA), bImageUri)
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
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(media, "image/jpeg"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            null,
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
        val skipped = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(media, "image/jpeg"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        )

        assertTrue(skipped)
        assertEquals(coreBasenames().size + 1, filebase.saveArtifactCalls.size)
        assertEquals(1, listener.successes.size)
        assertEquals(hash to expectedImageUri, listener.successes.single())
    }

    @Test
    fun concurrentStampMatchWaiters_stampSkipWithoutStackOverflow() {
        // Verifies that N concurrent enqueues for the same hash:
        //   (a) do not cause stack overflow (no recursive drain),
        //   (b) yield exactly one actual upload, with remaining callers receiving stamp-skip.
        //
        // Uses real Dispatchers.IO for genuine thread concurrency. A CountDownLatch inside the
        // fake uploadDirectory blocks the first coroutine inside withLock so the test can
        // enqueue additional callers while the Mutex is held, then release and verify outcomes.
        ProofSetUploader.clearMapsForTesting()  // real Dispatchers.IO for this test

        val primary = AccumulatingStorageProvider()
        for (name in coreBasenames()) {
            primary.saveBytes(hash, name, name.toByteArray(), null)
        }

        val uploadStartedLatch = CountDownLatch(1)
        val uploadContinueLatch = CountDownLatch(1)
        val allCompletedLatch = CountDownLatch(3)

        val filebase = object : RecordingFilebaseStorageProvider() {
            override fun uploadDirectory(
                hash: String,
                artifacts: List<DeferredArtifact>,
                mediaBasename: String?,
                listener: StorageListener?,
            ): FilebaseUploadResult? {
                uploadDirectoryCalls.add(
                    hash to artifacts.map { DeferredArtifact.ofBytes(it.identifier, it.readAllBytes(), it.contentType) },
                )
                uploadStartedLatch.countDown()
                // Block inside withLock so the test can enqueue concurrent callers.
                // Dispatchers.IO is designed for blocking calls; this is safe.
                uploadContinueLatch.await(10, TimeUnit.SECONDS)
                listener?.saveSuccessful(hash, uploadDirectoryResultUri)
                return FilebaseUploadResult(uploadDirectoryResultUri, uploadDirectoryMediaLeafCid)
            }
        }

        fun makeListener() = object : StorageListener {
            override fun saveSuccessful(h: String?, u: String?) { allCompletedLatch.countDown() }
            override fun saveFailed(e: Exception?) { allCompletedLatch.countDown() }
        }

        // First enqueue: pre-enqueue gate passes, coroutine scheduled on IO, acquires Mutex.
        val r1 = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            makeListener(),
        )
        assertTrue(r1)

        // Wait for Coroutine1 to be inside uploadDirectory (holding the Mutex).
        assertTrue("Upload should start within 5s", uploadStartedLatch.await(5, TimeUnit.SECONDS))

        // Enqueue 2 and 3 while Mutex is held; their coroutines suspend at mutex.withLock.
        val r2 = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            makeListener(),
        )
        val r3 = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            makeListener(),
        )
        assertTrue(r2); assertTrue(r3)

        // Release Coroutine1's upload. Coroutine2 and Coroutine3 will wake up sequentially,
        // reassemble fresh state, find stamp match, and receive stamp-skip (no second upload).
        uploadContinueLatch.countDown()

        assertTrue("All 3 listeners should fire within 10s", allCompletedLatch.await(10, TimeUnit.SECONDS))
        assertEquals("Only one actual upload should occur", 1, filebase.uploadDirectoryCalls.size)

        // Restore Unconfined for any tests that run after this one in the suite.
        ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)
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
                ProofSetUploader.enqueueProofSetUpload(
            context,
            freshHash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            object : StorageListener {
                        override fun saveSuccessful(h: String?, u: String?) { allCompletedLatch.countDown() }
                        override fun saveFailed(e: Exception?) { allCompletedLatch.countDown() }
                    },
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
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(4, 5, 6), "image/png"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            null,
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

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        )

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
        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )
        val priorStamp = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(priorStamp != null && priorStamp.basenames.isNotEmpty())

        primary.unreadableIdentifiers.add(coreBasenames().first())
        val listener = RecordingListener()
        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        )

        assertTrue(started)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.successes.isEmpty())
        assertEquals(priorStamp, ProofSetUploader.lastUploadedMembership(hash))
    }

    @Test
    fun stampSkip_includeMedia_mediaNoLongerResolves_saveFailedNotSuccess() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )
        val priorStamp = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(priorStamp != null && priorStamp.basenames.isNotEmpty())

        // Media resolves for the pre-enqueue gate, then disappears before the coroutine takes
        // the lock — the post-acquire re-resolve must fail closed.
        val mediaFile = java.io.File(context.cacheDir, "$hash-vanishing-media.jpg")
        mediaFile.writeBytes(byteArrayOf(1, 2, 3))
        var resolveCalls = 0
        val listener = RecordingListener()
        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.fromUriProvider(context) {
                resolveCalls++
                if (resolveCalls == 1) {
                    Uri.fromFile(mediaFile) to "image/jpeg"
                } else {
                    Uri.parse("content://missing/media") to "image/jpeg"
                }
            },
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        )

        assertTrue(started)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.successes.isEmpty())
        assertEquals(priorStamp, ProofSetUploader.lastUploadedMembership(hash))
    }

    @Test
    fun incompleteFirstPass_stillReturnsFalse_withoutSaveFailed() {
        val incomplete = coreBasenames() - "$hash.proof.json"
        val primary = primaryWithCore(proofNames = incomplete)
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        )

        assertFalse(started)
        assertTrue(listener.failures.isEmpty())
        assertTrue(listener.successes.isEmpty())
    }

    @Test
    fun sidecarsOnly_assembleOmitsMediaLeaf_andAdvancesStampWithoutMediaBasename() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()
        val mediaBasename = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, "image/jpeg")

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            listener,
        )

        assertTrue(started)
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        val ids = filebase.uploadDirectoryCalls.single().second.map { it.identifier }
        assertFalse(mediaBasename in ids)
        assertTrue(coreBasenames().all { it in ids })

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

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            null,
        ),
        )
        assertEquals(1, filebase.uploadDirectoryCalls.size)

        val listener = RecordingListener()
        val skipped = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            listener,
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
        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            null,
        ),
        )
        val priorStamp = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(priorStamp != null && priorStamp.basenames.isNotEmpty())

        primary.unreadableIdentifiers.add(coreBasenames().first())
        val listener = RecordingListener()
        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            listener,
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

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        val includeStamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertEquals(MediaInclusion.INCLUDE_MEDIA, includeStamp.mediaInclusion)

        val listener = RecordingListener()
        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            listener,
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

        val started = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.SIDECARS_ONLY,
            listener,
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
    fun ipfs_sidecarsOnly_writesProofsetUri_notImageUri() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()
        val cid = FilebaseGatewayUris.parseGatewayRootCid(filebase.uploadDirectoryResultUri)!!

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            listener,
        ),
        )

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
        assertEquals(1, listener.successes.size)
        assertTrue(ProofSetUploader.lastUploadedMembership(hash) != null)
    }

    @Test
    fun ipfs_sidecarsOnly_leavesPreexistingImageUriUntouched() {
        val priorCid = "bafyPriorRoot"
        val priorImageUri = FilebaseGatewayUris.buildImageUriUnderDirectory(priorCid, "$hash.jpg")
        val primary = primaryWithDirectoryProvenance(priorCid)
        val filebase = RecordingFilebaseStorageProvider(
            uploadDirectoryResultUri = "https://ipfs.filebase.io/ipfs/bafyNewRoot",
        )

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            null,
        ),
        )

        assertFalse(
            primary.saveTextCalls.any {
                it.second == hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX
            },
        )
        val stillPrior = primary.getInputStream(
            hash,
            hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX,
        )!!.use { String(it.readBytes()) }
        assertEquals(priorImageUri, stillPrior)
    }

    @Test
    fun s3_sidecarsOnly_writesNeitherUri_stillSuccess() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.SIDECARS_ONLY,
            listener,
        ),
        )

        assertTrue(primary.saveTextCalls.isEmpty())
        assertEquals(1, listener.successes.size)
        assertTrue(listener.failures.isEmpty())
        assertEquals(MediaInclusion.SIDECARS_ONLY, ProofSetUploader.lastUploadedMembership(hash)!!.mediaInclusion)
    }

    @Test
    fun s3_includeMedia_writesImageOnly_notProofset() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val mediaBasename = "$hash.mp4"
        val mediaUri = "s3://bucket/$hash/$mediaBasename"

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(7, 8, 9), "video/mp4"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            null,
        ),
        )

        assertEquals(
            listOf(Triple(hash, hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX, mediaUri)),
            primary.saveTextCalls,
        )
        assertFalse(
            primary.saveTextCalls.any {
                it.second == hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX
            },
        )
    }

    @Test
    fun ipfs_includeMedia_unableToAuthorImage_returnsFailedNotSuccess() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider(uploadDirectoryMediaLeafCid = null)
        val artifacts = coreBasenames().map {
            DeferredArtifact.ofBytes(it, it.toByteArray(), "application/octet-stream")
        }

        val outcome = org.witness.proofmode.storage.filebase.IpfsDirectoryUploadStrategy.upload(
            primary,
            filebase,
            hash,
            artifacts,
            mediaBasename = null,
            mediaInclusion = MediaInclusion.INCLUDE_MEDIA,
        )

        assertTrue(outcome is ProofSetUploadOutcome.Failed)
        assertFalse(
            primary.saveTextCalls.any {
                it.second == hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX
            },
        )

        // Facade must not advance stamp when Strategy returns Failed
        val listener = RecordingListener()
        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primaryWithCore(),
            RecordingFilebaseStorageProvider(uploadDirectorySucceed = false),
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        ),
        )
        assertNull(ProofSetUploader.lastUploadedMembership(hash))
        assertTrue(listener.successes.isEmpty())
        assertEquals(1, listener.failures.size)
    }

    @Test
    fun stamp_s3SidecarsOnly_thenSwitchIpfs_sameBasenames_doesNotSkip_writesProofsetUri() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.SIDECARS_ONLY,
            listener,
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
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            switchListener,
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

    @Test
    fun stamp_ipfsSidecarsOnly_thenSwitchS3_sameBasenames_doesNotSkip() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.IPFS_DIRECTORY,
            MediaInclusion.SIDECARS_ONLY,
            listener,
        ),
        )
        assertEquals(1, filebase.uploadDirectoryCalls.size)
        assertEquals(1, listener.successes.size)

        val ipfsStamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertEquals(FilebaseConfig.UploadMode.IPFS_DIRECTORY, ipfsStamp.uploadMode)
        assertEquals(MediaInclusion.SIDECARS_ONLY, ipfsStamp.mediaInclusion)

        val switchListener = RecordingListener()
        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.SIDECARS_ONLY,
            switchListener,
        ),
        )

        assertEquals(coreBasenames().size, filebase.saveArtifactCalls.size)
        assertTrue(filebase.uploadDirectoryCalls.size == 1)
        val s3Stamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertEquals(FilebaseConfig.UploadMode.S3_MEMBERS, s3Stamp.uploadMode)
        assertEquals(ipfsStamp.basenames, s3Stamp.basenames)
        assertNotEquals(ipfsStamp, s3Stamp)
        assertEquals(1, switchListener.successes.size)
    }

    @Test
    fun stamp_s3SidecarsOnly_secondSameModeEnqueue_stampSkips() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider()
        val listener = RecordingListener()

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.SIDECARS_ONLY,
            listener,
        ),
        )
        assertEquals(coreBasenames().size, filebase.saveArtifactCalls.size)
        assertEquals(1, listener.successes.size)
        val priorStamp = ProofSetUploader.lastUploadedMembership(hash)!!

        val skipListener = RecordingListener()
        val skipped = ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            null,
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.SIDECARS_ONLY,
            skipListener,
        )

        assertTrue(skipped)
        assertEquals(coreBasenames().size, filebase.saveArtifactCalls.size)
        assertEquals(priorStamp, ProofSetUploader.lastUploadedMembership(hash))
        assertEquals(1, skipListener.successes.size)
        assertTrue(skipListener.failures.isEmpty())
    }

    @Test
    fun s3_includeMedia_mediaMemberUriMissing_returnsFailedNotSuccessNull() {
        val primary = primaryWithCore()
        val filebase = RecordingFilebaseStorageProvider(saveArtifactNullUri = true)
        val listener = RecordingListener()

        assertTrue(
            ProofSetUploader.enqueueProofSetUpload(
            context,
            hash,
            primary,
            filebase,
            ProofSetMediaSource.ofBytes(byteArrayOf(1, 2, 3), "image/jpeg"),
            FilebaseConfig.UploadMode.S3_MEMBERS,
            MediaInclusion.INCLUDE_MEDIA,
            listener,
        ),
        )

        assertTrue(primary.saveTextCalls.isEmpty())
        assertNull(ProofSetUploader.lastUploadedMembership(hash))
        assertTrue(listener.successes.isEmpty())
        assertEquals(1, listener.failures.size)
    }
}

/** Test double that records saveBytes / getProofSet / getInputStream / saveText (primary storage). */
open class RecordingStorageProvider(
    private val proofSetUris: ArrayList<Uri> = arrayListOf(),
    private val streams: Map<String, ByteArray> = emptyMap(),
    private val markerIdentifiers: Set<String> = emptySet(),
    var saveArtifactSucceed: Boolean = true,
    var saveArtifactResultUriPrefix: String = "s3://bucket/",
) : StorageProvider {
    val saveArtifactCalls = mutableListOf<Triple<String, String, ByteArray>>()
    val getProofSetCalls = mutableListOf<String>()
    val getInputStreamCalls = mutableListOf<Pair<String, String>>()
    val saveTextCalls = mutableListOf<Triple<String, String, String>>()
    /** When set, [getInputStream] returns null for these identifiers (even if present in streams). */
    val unreadableIdentifiers = mutableSetOf<String>()

    override fun saveStream(hash: String?, identifier: String?, stream: InputStream?, listener: StorageListener?) {
        throw UnsupportedOperationException()
    }

    override fun saveBytes(hash: String?, identifier: String?, data: ByteArray?, listener: StorageListener?) {
        saveArtifactCalls.add(Triple(hash!!, identifier!!, data!!.copyOf()))
        if (saveArtifactSucceed) {
            listener?.saveSuccessful(hash, "$saveArtifactResultUriPrefix$hash/$identifier")
        } else {
            listener?.saveFailed(RuntimeException("saveBytes failed"))
        }
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
        identifier in markerIdentifiers || streams.containsKey(identifier)

    override fun getProofSet(hash: String?): ArrayList<Uri> {
        getProofSetCalls.add(hash!!)
        return ArrayList(proofSetUris)
    }

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
    var saveArtifactSucceed: Boolean = true,
    var saveArtifactResultUriPrefix: String = "s3://bucket/",
    /** When true, [saveArtifact] reports success with a null URI (media member URI missing). */
    var saveArtifactNullUri: Boolean = false,
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
        if (saveArtifactSucceed) {
            val uri =
                if (saveArtifactNullUri) null else "$saveArtifactResultUriPrefix$hash/${artifact.identifier}"
            listener?.saveSuccessful(hash, uri)
        } else {
            listener?.saveFailed(RuntimeException("saveArtifact failed"))
        }
    }
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
