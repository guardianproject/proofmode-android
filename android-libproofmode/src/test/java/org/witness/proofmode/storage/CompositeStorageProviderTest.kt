package org.witness.proofmode.storage

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import org.witness.proofmode.ProofMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import org.witness.proofmode.storage.filebase.FilebaseUploadResult
import org.witness.proofmode.storage.proofset.DeferredArtifact
import org.witness.proofmode.storage.proofset.MediaInclusion
import org.witness.proofmode.storage.proofset.MembershipStamp
import org.witness.proofmode.storage.proofset.ProofSetMembershipPolicy
import org.witness.proofmode.storage.proofset.readAllBytes
import org.witness.proofmode.storage.proofset.ProofSetUploader
import org.witness.proofmode.storage.proofset.RecordingFilebaseStorageProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CompositeStorageProviderTest {
    private val hash = "abc123deadbeef"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)
    }

    private fun coreBasenames(): Set<String> =
        ProofSetMembershipPolicy.requiredCoreBasenames(hash)

    private fun ipfsConfig(autoIncludeMedia: Boolean = true) = FilebaseConfig(
        accessKey = "ak",
        secretKey = "sk",
        bucketName = "bucket",
        enabled = true,
        ipfsBearerToken = "token",
        autoUpload = true,
        autoIncludeMedia = autoIncludeMedia,
    )

    private fun s3Config(autoIncludeMedia: Boolean = true) = FilebaseConfig(
        accessKey = "ak",
        secretKey = "sk",
        bucketName = "bucket",
        enabled = true,
        ipfsBearerToken = "",
        autoUpload = true,
        autoIncludeMedia = autoIncludeMedia,
    )

    private fun mediaUri(bytes: ByteArray = byteArrayOf(1, 2, 3)): Uri {
        val file = File(context.cacheDir, "$hash-media.jpg")
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    /** Content Uri that increments [openCount] and throws if ContentResolver opens it. */
    private fun untouchableMediaUri(openCount: AtomicInteger): Uri {
        val uri = Uri.parse("content://org.witness.proofmode.test.untouchable/media")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, object : InputStream() {
                override fun read(): Int {
                    openCount.incrementAndGet()
                    throw IOException("media Uri must not be opened under SIDECARS_ONLY")
                }
            })
        return uri
    }

    /** Media Uri that reports [size] via the SIZE column and throws if its bytes are read. */
    private fun sizedButUnreadableMediaUri(size: Long, reads: AtomicInteger): Uri {
        val uri = Uri.parse("content://org.witness.proofmode.test.sized/media")
        val cursor = RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any>(size)))
        }
        Shadows.shadowOf(context.contentResolver).setCursor(uri, cursor)
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, object : InputStream() {
                override fun read(): Int {
                    reads.incrementAndGet()
                    throw IOException("media content must not be read to flush a proof set")
                }
            })
        return uri
    }

    private fun deferredComposite(
        primary: AccumulatingStorageProvider = AccumulatingStorageProvider(),
        secondary: RecordingFilebaseStorageProvider = RecordingFilebaseStorageProvider(),
        config: FilebaseConfig = ipfsConfig(autoIncludeMedia = true),
    ): Triple<CompositeStorageProvider, AccumulatingStorageProvider, RecordingFilebaseStorageProvider> {
        val composite = CompositeStorageProvider(
            primary,
            secondary,
            context,
            deferProofSetUpload = true,
            filebaseConfig = config,
        )
        return Triple(composite, primary, secondary)
    }

    @Test
    fun tryFlush_sidecarsOnly_doesNotReadMediaBytes_stillEnqueues() {
        val openCount = AtomicInteger(0)
        val (composite, _, secondary) = deferredComposite(config = ipfsConfig(autoIncludeMedia = false))
        composite.bindMedia(hash, untouchableMediaUri(openCount), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        val capture = ProofSetUploader.lastEnqueueForTesting
        assertNotNull(capture)
        assertNull(capture!!.mediaLength)
        assertEquals(MediaInclusion.SIDECARS_ONLY, capture.mediaInclusion)
        assertFalse(capture.hasMediaSource)
        assertEquals(0, openCount.get())
    }

    @Test
    fun tryFlush_includeMedia_passesMediaSource_andIncludeMedia() {
        val mediaBytes = byteArrayOf(9, 8, 7)
        val (composite, _, secondary) = deferredComposite(config = ipfsConfig(autoIncludeMedia = true))
        composite.bindMedia(hash, mediaUri(mediaBytes), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        val capture = ProofSetUploader.lastEnqueueForTesting
        assertNotNull(capture)
        assertEquals(mediaBytes.size.toLong(), capture!!.mediaLength)
        assertEquals(MediaInclusion.INCLUDE_MEDIA, capture.mediaInclusion)
        assertTrue(capture.hasMediaSource)
        assertEquals(FilebaseConfig.UploadMode.IPFS_DIRECTORY, capture.mode)
    }

    @Test
    fun autoIncludeMedia_s3Members_enqueuesMediaLeafAndWritesImageUri() {
        val mediaBytes = byteArrayOf(1, 2, 3)
        val mediaBasename = "$hash.jpg"
        val expectedImageUri = "s3://bucket/$hash/$mediaBasename"
        val (composite, primary, secondary) = deferredComposite(config = s3Config(autoIncludeMedia = true))
        composite.bindMedia(hash, mediaUri(mediaBytes), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        val capture = ProofSetUploader.lastEnqueueForTesting
        assertNotNull(capture)
        assertEquals(FilebaseConfig.UploadMode.S3_MEMBERS, capture!!.mode)
        assertEquals(MediaInclusion.INCLUDE_MEDIA, capture.mediaInclusion)
        assertEquals(mediaBytes.size.toLong(), capture.mediaLength)
        assertTrue(capture.hasMediaSource)

        val mediaUpload = secondary.saveArtifactCalls.find { it.second == mediaBasename }
        assertNotNull(mediaUpload)
        assertArrayEquals(mediaBytes, mediaUpload!!.third)
        assertTrue(secondary.uploadDirectoryCalls.isEmpty())

        val imageSidecarUri = primary.getInputStream(
            hash,
            hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX,
        )?.bufferedReader()?.use { it.readText() }
        assertEquals(expectedImageUri, imageSidecarUri)
        assertNull(
            primary.getInputStream(hash, hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX),
        )
    }

    /**
     * INCLUDE_MEDIA with membership still incomplete: tryFlush runs on every sidecar save, so the
     * media must be measured from metadata only. Reading it here is what blew the heap on video.
     */
    @Test
    fun tryFlush_includeMedia_incompleteMembership_doesNotReadMediaContent() {
        val reads = AtomicInteger(0)
        val (composite, _, secondary) = deferredComposite(config = ipfsConfig(autoIncludeMedia = true))
        composite.bindMedia(hash, sizedButUnreadableMediaUri(10L * 1024 * 1024, reads), "video/mp4")

        for (name in coreBasenames() - "$hash.proof.json") {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertTrue(secondary.uploadDirectoryCalls.isEmpty())
        assertEquals(0, reads.get())
        // The media was still sized for the enqueue gate, not skipped outright.
        assertEquals(10L * 1024 * 1024, ProofSetUploader.lastEnqueueForTesting?.mediaLength)
    }

    @Test
    fun tryFlush_s3Members_defersThroughFacade_notImmediateSecondary() {
        val (composite, _, secondary) = deferredComposite(config = s3Config(autoIncludeMedia = true))
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        val capture = ProofSetUploader.lastEnqueueForTesting
        assertNotNull(capture)
        assertEquals(FilebaseConfig.UploadMode.S3_MEMBERS, capture!!.mode)
        assertEquals(MediaInclusion.INCLUDE_MEDIA, capture.mediaInclusion)
        assertTrue(secondary.uploadDirectoryCalls.isEmpty())
        // S3 strategy uploads via saveBytes through Facade — members present after enqueue.
        assertTrue(secondary.saveArtifactCalls.isNotEmpty())
    }

    @Test
    fun tryFlush_stampPeekUsesFullMembershipStamp() {
        val (composite, _, secondary) = deferredComposite(config = ipfsConfig(autoIncludeMedia = false))
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }
        assertEquals(1, secondary.uploadDirectoryCalls.size)

        val stamp = ProofSetUploader.lastUploadedMembership(hash)
        assertNotNull(stamp)
        assertTrue(stamp is MembershipStamp)
        assertEquals(MediaInclusion.SIDECARS_ONLY, stamp!!.mediaInclusion)
        assertEquals(FilebaseConfig.UploadMode.IPFS_DIRECTORY, stamp.uploadMode)
        val mediaName = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, "image/jpeg")
        assertTrue(mediaName !in stamp.basenames)

        // Identical membership: re-save existing core — stamp peek skips enqueue.
        composite.saveBytes(hash, coreBasenames().first(), byteArrayOf(7), null)
        assertEquals(1, secondary.uploadDirectoryCalls.size)
        assertEquals(stamp, ProofSetUploader.lastUploadedMembership(hash))
    }

    @Test
    fun tryFlush_sidecarsOnly_stampOmitsMediaBasename() {
        val mediaName = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, "image/jpeg")
        assertEquals("$hash.jpg", mediaName)

        val (sidecarsComposite, _, sidecarsSecondary) =
            deferredComposite(config = ipfsConfig(autoIncludeMedia = false))
        sidecarsComposite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            sidecarsComposite.saveBytes(hash, name, name.toByteArray(), null)
        }
        assertEquals(1, sidecarsSecondary.uploadDirectoryCalls.size)
        val sidecarsStamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertTrue(mediaName !in sidecarsStamp.basenames)
        assertEquals(
            ProofSetUploader.buildMembershipStamp(
                hash,
                FilebaseConfig.UploadMode.IPFS_DIRECTORY,
                MediaInclusion.SIDECARS_ONLY,
                coreBasenames(),
                "image/jpeg",
            ),
            sidecarsStamp,
        )

        ProofSetUploader.clearMapsForTesting(Dispatchers.Unconfined)
        val (includeComposite, _, includeSecondary) =
            deferredComposite(config = ipfsConfig(autoIncludeMedia = true))
        includeComposite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            includeComposite.saveBytes(hash, name, name.toByteArray(), null)
        }
        assertEquals(1, includeSecondary.uploadDirectoryCalls.size)
        val includeStamp = ProofSetUploader.lastUploadedMembership(hash)!!
        assertTrue(mediaName in includeStamp.basenames)
        assertEquals(
            ProofSetUploader.buildMembershipStamp(
                hash,
                FilebaseConfig.UploadMode.IPFS_DIRECTORY,
                MediaInclusion.INCLUDE_MEDIA,
                coreBasenames(),
                "image/jpeg",
            ),
            includeStamp,
        )
    }

    @Test
    fun bindMediaBeforeCoreSaves_uploadsOnceAfterLastCore() {
        val (composite, _, secondary) = deferredComposite()
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        assertEquals(0, secondary.uploadDirectoryCalls.size)

        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        assertTrue(secondary.saveArtifactCalls.isEmpty())
    }

    @Test
    fun bindMediaAfterLastCoreSave_stillUploadsOnceViaBindMedia() {
        val (composite, _, secondary) = deferredComposite()

        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }
        assertEquals(0, secondary.uploadDirectoryCalls.size)

        composite.bindMedia(hash, mediaUri(), "image/jpeg")

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        assertTrue(secondary.saveArtifactCalls.isEmpty())
    }

    @Test
    fun deferredPath_doesNotCallSecondarySaveBytesPerArtifact() {
        val (composite, _, secondary) = deferredComposite()
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertTrue(secondary.saveArtifactCalls.isEmpty())
        assertEquals(1, secondary.uploadDirectoryCalls.size)
    }

    @Test
    fun secondFlush_changedMembershipReuploads_unchangedSkips() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()

        val (composite, _, secondary) = deferredComposite()
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }
        assertEquals(1, secondary.uploadDirectoryCalls.size)
        val stampAfterFirstUpload = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue(stampAfterFirstUpload != null && stampAfterFirstUpload.basenames.isNotEmpty())

        composite.saveBytes(hash, "$hash.ots", byteArrayOf(9), null)
        assertEquals(2, secondary.uploadDirectoryCalls.size)
        val stampAfterOts = ProofSetUploader.lastUploadedMembership(hash)
        assertTrue("$hash.ots" in stampAfterOts!!.basenames)

        // Unchanged membership: re-save an existing core artifact — pre-acquire stamp short-circuit
        composite.saveBytes(hash, coreBasenames().first(), byteArrayOf(7), null)
        assertEquals(2, secondary.uploadDirectoryCalls.size)
        assertEquals(stampAfterOts, ProofSetUploader.lastUploadedMembership(hash))

        // Excluded URI tip also must not trigger another RPC
        composite.replaceText(hash, "$hash.proof.csv.uri", "s3://ignored", null)
        assertEquals(2, secondary.uploadDirectoryCalls.size)
    }

    @Test
    fun replaceText_isPrimaryOnly_doesNotFanOutOrNestUriTips() {
        val primary = AccumulatingStorageProvider()
        var secondaryReplaceCalls = 0
        val secondary = object : RecordingFilebaseStorageProvider() {
            override fun replaceText(
                hash: String,
                identifier: String,
                data: String,
                listener: StorageListener?,
            ) {
                secondaryReplaceCalls++
                listener?.saveSuccessful(hash, "s3://bucket/$identifier")
            }
        }
        val composite = CompositeStorageProvider(
            primary,
            secondary,
            context,
            deferProofSetUpload = false,
        )
        val tipId = "$hash.filebase.ipfs.uri"
        val tip = "https://ipfs.filebase.io/ipfs/bafytest"

        composite.replaceText(hash, tipId, tip, null)

        assertEquals(tip, String(primary.getInputStream(hash, tipId)!!.readBytes()))
        assertEquals(0, secondaryReplaceCalls)
        assertTrue(!primary.proofIdentifierExists(hash, "$tipId.uri"))
    }

    @Test
    fun failedUpload_sameMembership_doesNotAutoRetry() {
        var failuresRemaining = 1
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
                return if (failuresRemaining > 0) {
                    failuresRemaining--
                    null
                } else {
                    FilebaseUploadResult(uploadDirectoryResultUri, leafCid)
                }
            }
        }
        val (composite, _, _) = deferredComposite(secondary = secondary)
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        // Failure alone must not re-enter; membership stamp never advanced.
        assertEquals(1, secondary.uploadDirectoryCalls.size)
        assertEquals(0, failuresRemaining)
    }

    @Test
    fun failedUpload_retriesWhenPendingSetDuringInFlight() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()

        lateinit var composite: CompositeStorageProvider
        var failuresRemaining = 1
        val primary = AccumulatingStorageProvider()
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
                if (uploadDirectoryCalls.size == 1) {
                    // Concurrent membership change while in-flight sets flushPending.
                    composite.saveBytes(hash, "$hash.ots", byteArrayOf(9), null)
                }
                val leafCid = if (!mediaBasename.isNullOrBlank()) uploadDirectoryMediaLeafCid else null
                return if (failuresRemaining > 0) {
                    failuresRemaining--
                    null
                } else {
                    FilebaseUploadResult(uploadDirectoryResultUri, leafCid)
                }
            }
        }
        composite = CompositeStorageProvider(
            primary,
            secondary,
            context,
            deferProofSetUpload = true,
            filebaseConfig = ipfsConfig(),
        )
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        // First attempt fails with pending set; parked waiter retries after mutex release.
        assertEquals(2, secondary.uploadDirectoryCalls.size)
        assertEquals(0, failuresRemaining)
        val secondIds = secondary.uploadDirectoryCalls[1].second.map { it.identifier }
        assertTrue("$hash.ots" in secondIds)
    }

    @Test
    fun membershipChangeWhileInFlight_reFlushAfterSuccess() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()

        lateinit var composite: CompositeStorageProvider
        val primary = AccumulatingStorageProvider()
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
                if (uploadDirectoryCalls.size == 1) {
                    // Concurrent membership change while in-flight (lost-wake on atomic guard).
                    composite.saveBytes(hash, "$hash.ots", byteArrayOf(9), null)
                }
                val leafCid = if (!mediaBasename.isNullOrBlank()) uploadDirectoryMediaLeafCid else null
                return FilebaseUploadResult(uploadDirectoryResultUri, leafCid)
            }
        }
        composite = CompositeStorageProvider(
            primary,
            secondary,
            context,
            deferProofSetUpload = true,
            filebaseConfig = ipfsConfig(),
        )
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertEquals(2, secondary.uploadDirectoryCalls.size)
        val secondIds = secondary.uploadDirectoryCalls[1].second.map { it.identifier }
        assertTrue("$hash.ots" in secondIds)
    }

    @Test
    fun mapsSurviveNewCompositeInstance() {
        val (compositeA, primary, secondary) = deferredComposite()
        compositeA.bindMedia(hash, mediaUri(), "image/jpeg")
        for (name in coreBasenames()) {
            compositeA.saveBytes(hash, name, name.toByteArray(), null)
        }
        assertEquals(1, secondary.uploadDirectoryCalls.size)

        val compositeB = CompositeStorageProvider(
            primary,
            secondary,
            context,
            deferProofSetUpload = true,
            filebaseConfig = ipfsConfig(),
        )
        compositeB.bindMedia(hash, mediaUri(), "image/jpeg")
        compositeB.saveBytes(hash, coreBasenames().first(), byteArrayOf(7), null)

        assertEquals(1, secondary.uploadDirectoryCalls.size)
    }

    @Test
    fun incompleteFirstPass_releasesInFlight_noUnpin() {
        val (composite, _, secondary) = deferredComposite()
        composite.bindMedia(hash, mediaUri(), "image/jpeg")
        val incomplete = coreBasenames() - "$hash.proof.json"
        for (name in incomplete) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertTrue(secondary.uploadDirectoryCalls.isEmpty())
    }

    @Test
    fun tryFlush_includeMedia_overMediaLimit_enqueuesSidecarsOnly_withoutOpeningMedia() {
        val reads = AtomicInteger(0)
        val overLimitBytes = FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L
        val (composite, _, secondary) = deferredComposite(config = ipfsConfig(autoIncludeMedia = true))
        composite.bindMedia(
            hash,
            sizedButUnreadableMediaUri(overLimitBytes, reads),
            "video/mp4",
        )
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        val capture = ProofSetUploader.lastEnqueueForTesting
        assertNotNull(capture)
        assertEquals(MediaInclusion.SIDECARS_ONLY, capture!!.mediaInclusion)
        assertFalse(capture.hasMediaSource)
        assertNull(capture.mediaLength)
        assertEquals(0, reads.get())

        val mediaName = ProofSetMembershipPolicy.manifestLinkNameForMedia(hash, "video/mp4")
        val stamp = ProofSetUploader.lastUploadedMembership(hash)
        assertNotNull(stamp)
        assertEquals(MediaInclusion.SIDECARS_ONLY, stamp!!.mediaInclusion)
        assertTrue(mediaName !in stamp.basenames)
    }

    @Test
    fun tryFlush_includeMedia_withinMediaLimit_stillIncludeMedia() {
        val mediaBytes = byteArrayOf(9, 8, 7, 6)
        val (composite, _, secondary) = deferredComposite(config = ipfsConfig(autoIncludeMedia = true))
        composite.bindMedia(hash, mediaUri(mediaBytes), "image/jpeg")
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        val capture = ProofSetUploader.lastEnqueueForTesting
        assertNotNull(capture)
        assertEquals(MediaInclusion.INCLUDE_MEDIA, capture!!.mediaInclusion)
        assertTrue(capture.hasMediaSource)
        assertEquals(mediaBytes.size.toLong(), capture.mediaLength)
        assertTrue(
            FilebaseConfig.isWithinFilebaseMediaLimit(capture.mediaLength!!),
        )
    }

    @Test
    fun tryFlush_autoIncludeOff_oversizeMedia_stillSidecarsOnly() {
        val reads = AtomicInteger(0)
        val overLimitBytes = FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L
        val (composite, _, secondary) = deferredComposite(config = ipfsConfig(autoIncludeMedia = false))
        composite.bindMedia(
            hash,
            sizedButUnreadableMediaUri(overLimitBytes, reads),
            "video/mp4",
        )
        for (name in coreBasenames()) {
            composite.saveBytes(hash, name, name.toByteArray(), null)
        }

        assertEquals(1, secondary.uploadDirectoryCalls.size)
        val capture = ProofSetUploader.lastEnqueueForTesting
        assertNotNull(capture)
        assertEquals(MediaInclusion.SIDECARS_ONLY, capture!!.mediaInclusion)
        assertFalse(capture.hasMediaSource)
        assertNull(capture.mediaLength)
        assertEquals(0, reads.get())
    }
}

/** Primary that accumulates proof-set members as saves occur. */
class AccumulatingStorageProvider : StorageProvider {
    private val stored = ConcurrentHashMap<String, ByteArray>()

    override fun saveStream(hash: String?, identifier: String?, stream: InputStream?, listener: StorageListener?) {
        stored[identifier!!] = stream!!.readBytes()
        listener?.saveSuccessful(hash, "file:///$identifier")
    }

    override fun saveBytes(hash: String?, identifier: String?, data: ByteArray?, listener: StorageListener?) {
        stored[identifier!!] = data!!.copyOf()
        listener?.saveSuccessful(hash, "file:///$identifier")
    }

    override fun saveText(hash: String?, identifier: String?, data: String?, listener: StorageListener?) {
        stored[identifier!!] = (data ?: "").toByteArray()
        listener?.saveSuccessful(hash, "file:///$identifier")
    }

    override fun replaceText(hash: String?, identifier: String?, data: String?, listener: StorageListener?) {
        stored[identifier!!] = (data ?: "").toByteArray()
        listener?.saveSuccessful(hash, "file:///$identifier")
    }

    override fun getInputStream(hash: String?, identifier: String?): InputStream? {
        val bytes = stored[identifier] ?: return null
        return ByteArrayInputStream(bytes)
    }

    override fun proofExists(hash: String?): Boolean = stored.isNotEmpty()

    override fun proofIdentifierExists(hash: String?, identifier: String?): Boolean =
        stored.containsKey(identifier)

    override fun getProofSet(hash: String?): ArrayList<Uri> =
        ArrayList(stored.keys.map { Uri.parse("file:///proof/$it") })

    override fun getProofItem(uri: Uri?): InputStream? = null

    fun removeMemberForTesting(identifier: String) {
        stored.remove(identifier)
    }
}
