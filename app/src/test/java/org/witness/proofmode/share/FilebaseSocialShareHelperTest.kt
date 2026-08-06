package org.witness.proofmode.share

import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseStorageProvider
import org.witness.proofmode.storage.proofset.DeferredArtifact
import org.witness.proofmode.storage.proofset.ProofSetMediaSource
import org.witness.proofmode.storage.proofset.ResolvedMedia
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * JVM unit tests for the social verify-URL ladder.
 *
 * [FilebaseSocialShareHelper.resolveSocialVerifyUrl] takes a [ProofSetMediaSource] rather than a
 * `ContentResolver`/`Uri` pair, so the whole ladder — including the upload rung — is driveable
 * from a plain JVM test (the app module has only `testImplementation(libs.junit)`, no Robolectric).
 * The provider's network calls are stubbed by overriding them in [FakeFilebase].
 */
class FilebaseSocialShareHelperTest {

    private val HASH = "deadbeef1234"
    private val CID = "QmTestCid1234"
    private val GATEWAY_URI = "https://ipfs.filebase.io/ipfs/$CID"
    private val EXISTING_IMAGE_URL = "https://ipfs.filebase.io/ipfs/$CID/$HASH.jpg"

    /** Minimal fake [StorageProvider] that stores text via [saveText]/[replaceText] and serves it via [getInputStream]. */
    private inner class FakeStorageProvider : StorageProvider {
        val saved = mutableMapOf<String, String>()

        fun put(hash: String, identifier: String, value: String) {
            saved[key(hash, identifier)] = value
        }

        override fun getInputStream(hash: String, identifier: String): InputStream? =
            saved[key(hash, identifier)]?.let { ByteArrayInputStream(it.toByteArray()) }

        override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {
            saved[key(hash, identifier)] = data
        }

        override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener?) {
            saved[key(hash, identifier)] = data
        }

        override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) = Unit
        override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener?) = Unit
        override fun proofExists(hash: String): Boolean = false
        override fun proofIdentifierExists(hash: String, identifier: String): Boolean = false
        override fun getProofSet(hash: String): ArrayList<Uri> = ArrayList()
        override fun getProofItem(uri: Uri): InputStream? = null

        private fun key(hash: String, identifier: String) = "$hash|$identifier"
    }

    @Test
    fun deriveAndPersist_noDirectoryUri_returnsNull() {
        val primary = FakeStorageProvider()
        val result = FilebaseSocialShareHelper.deriveAndPersistFromDirectory(primary, HASH, "image/jpeg")
        assertNull(result)
    }

    /** MVP: default mediaWasInPinSet=false → null, no invent from proofset alone. */
    @Test
    fun deriveAndPersist_withDirectoryUri_returnsDerivedUrl() {
        val primary = FakeStorageProvider()
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX}", GATEWAY_URI)

        val result = FilebaseSocialShareHelper.deriveAndPersistFromDirectory(primary, HASH, "image/jpeg")

        assertNull(result)
        assertNull(primary.saved["$HASH|$HASH${FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX}"])
    }

    /** MVP: default gate → no image sidecar persisted from proofset-only. */
    @Test
    fun deriveAndPersist_withDirectoryUri_persistsDerivedUrl() {
        val primary = FakeStorageProvider()
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX}", GATEWAY_URI)

        val derived = FilebaseSocialShareHelper.deriveAndPersistFromDirectory(primary, HASH, "image/jpeg")

        assertNull(derived)
        assertNull(primary.saved["$HASH|$HASH${FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX}"])
    }

    /** Future marker path: when mediaWasInPinSet=true, invent + persist still works. */
    @Test
    fun deriveAndPersist_mediaWasInPinSet_inventsAndPersists() {
        val primary = FakeStorageProvider()
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX}", GATEWAY_URI)

        val derived = FilebaseSocialShareHelper.deriveAndPersistFromDirectory(
            primary, HASH, "image/jpeg", mediaWasInPinSet = true,
        )

        assertNotNull(derived)
        assertEquals("https://ipfs.filebase.io/ipfs/$CID/$HASH.jpg", derived)
        assertEquals(
            derived,
            primary.saved["$HASH|$HASH${FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX}"],
        )
    }

    @Test
    fun social_doesNotDeriveImageFromProofsetOnly_sidecarsOnlyPin() {
        val primary = FakeStorageProvider()
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX}", GATEWAY_URI)

        assertNull(FilebaseSocialShareHelper.deriveAndPersistFromDirectory(primary, HASH, "image/jpeg"))
        assertNull(primary.saved["$HASH|$HASH${FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX}"])
    }

    @Test
    fun social_reusesExistingImageSidecar() {
        val primary = FakeStorageProvider()
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX}", EXISTING_IMAGE_URL)
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX}", GATEWAY_URI)

        assertEquals(
            EXISTING_IMAGE_URL,
            FilebaseSocialShareHelper.readProofText(
                primary, HASH, HASH + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX,
            ),
        )
        assertNull(FilebaseSocialShareHelper.deriveAndPersistFromDirectory(primary, HASH, "image/jpeg"))
    }

    // --- Ladder: reuse an already-pinned media leaf instead of re-uploading ---

    /** Fake provider: no sockets, and it records whether the media was uploaded. */
    private inner class FakeFilebase(
        private val directoryLeafCid: String? = null,
    ) : FilebaseStorageProvider(
        accessKey = "a",
        secretKey = "s",
        bucketName = "b",
        ipfsBearerToken = "tok",
    ) {
        var uploadedArtifact: DeferredArtifact? = null
            private set
        var lsRequest: Pair<String, String>? = null
            private set

        override fun findIpfsDirectoryLeafCid(directoryCid: String, name: String): String? {
            lsRequest = directoryCid to name
            return directoryLeafCid
        }

        override fun uploadFileIpfs(artifact: DeferredArtifact): String? {
            uploadedArtifact = artifact
            return "https://ipfs.filebase.io/ipfs/bafyFreshLeaf"
        }
    }

    private val ipfsConfig = FilebaseConfig(
        accessKey = "",
        secretKey = "",
        bucketName = "",
        enabled = true,
        ipfsBearerToken = "tok",
    )

    /** A media handle that fails the test if anything reads it. */
    private fun mediaSource(length: Long = 304_022_072L): ProofSetMediaSource =
        ProofSetMediaSource {
            ResolvedMedia("video/mp4", length) { ByteArrayInputStream(ByteArray(0)) }
        }

    @Test
    fun social_reusesMediaLeafAlreadyInPinnedDirectory_withoutUploading() {
        val primary = FakeStorageProvider()
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX}", GATEWAY_URI)
        val filebase = FakeFilebase(directoryLeafCid = "bafyMediaLeaf")

        val result = FilebaseSocialShareHelper.resolveSocialVerifyUrl(
            primary, filebase, ipfsConfig, HASH, mediaSource(), "video/mp4",
        )

        assertEquals("https://ipfs.filebase.io/ipfs/bafyMediaLeaf", result.verifyUrl)
        assertNull(filebase.uploadedArtifact)
        assertFalse(result.leafAddFailed)
        assertEquals(CID to "$HASH.mp4", filebase.lsRequest)
        // Persisted, so the next share short-circuits on the first rung.
        assertEquals(
            "https://ipfs.filebase.io/ipfs/bafyMediaLeaf",
            primary.saved["$HASH|$HASH${FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX}"],
        )
    }

    @Test
    fun social_uploadsWhenPinnedDirectoryHasNoMediaLeaf() {
        val primary = FakeStorageProvider()
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX}", GATEWAY_URI)
        val filebase = FakeFilebase(directoryLeafCid = null)

        val result = FilebaseSocialShareHelper.resolveSocialVerifyUrl(
            primary, filebase, ipfsConfig, HASH, mediaSource(), "video/mp4",
        )

        assertEquals("https://ipfs.filebase.io/ipfs/bafyFreshLeaf", result.verifyUrl)
        assertNotNull(filebase.uploadedArtifact)
    }

    /** The upload rung passes a handle with a probed length — never materialized bytes. */
    @Test
    fun social_uploadStreamsMedia_withProbedLength() {
        val primary = FakeStorageProvider()
        val filebase = FakeFilebase()

        FilebaseSocialShareHelper.resolveSocialVerifyUrl(
            primary, filebase, ipfsConfig, HASH, mediaSource(length = 304_022_072L), "video/mp4",
        )

        val artifact = filebase.uploadedArtifact
        assertNotNull(artifact)
        assertEquals("$HASH.mp4", artifact!!.identifier)
        assertEquals("video/mp4", artifact.contentType)
        assertEquals(304_022_072L, artifact.length)
    }

    @Test
    fun social_unresolvableMedia_returnsNullWithoutClaimingFailure() {
        val primary = FakeStorageProvider()
        val filebase = FakeFilebase()

        val result = FilebaseSocialShareHelper.resolveSocialVerifyUrl(
            primary, filebase, ipfsConfig, HASH, ProofSetMediaSource { null }, "video/mp4",
        )

        assertNull(result.verifyUrl)
        assertNull(filebase.uploadedArtifact)
    }

    @Test
    fun social_existingImageSidecar_skipsDirectoryProbeAndUpload() {
        val primary = FakeStorageProvider()
        primary.put(HASH, "$HASH${FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX}", EXISTING_IMAGE_URL)
        val filebase = FakeFilebase(directoryLeafCid = "bafyMediaLeaf")

        val result = FilebaseSocialShareHelper.resolveSocialVerifyUrl(
            primary, filebase, ipfsConfig, HASH, mediaSource(), "video/mp4",
        )

        assertEquals(EXISTING_IMAGE_URL, result.verifyUrl)
        assertNull(filebase.lsRequest)
        assertNull(filebase.uploadedArtifact)
    }

    @Test
    fun social_noIpfsAccess_reportsNoLeafFailure() {
        val primary = FakeStorageProvider()
        val filebase = FakeFilebase()

        val result = FilebaseSocialShareHelper.resolveSocialVerifyUrl(
            primary,
            filebase,
            ipfsConfig.copy(ipfsBearerToken = ""),
            HASH,
            mediaSource(),
            "video/mp4",
        )

        assertNull(result.verifyUrl)
        assertFalse(result.leafAddFailed)
        assertNull(filebase.uploadedArtifact)
    }

    @Test
    fun resolveFromPinnedDirectory_returnsNull_withoutDirectorySidecar() {
        val primary = FakeStorageProvider()
        val filebase = FakeFilebase(directoryLeafCid = "bafyMediaLeaf")

        assertNull(
            FilebaseSocialShareHelper.resolveFromPinnedDirectory(primary, filebase, HASH, "video/mp4"),
        )
        assertNull(filebase.lsRequest)
    }

    @Test
    fun overviewFilebaseImageUrl_doesNotInventFromProofsetAlone() {
        assertNull(
            FilebaseSocialShareHelper.overviewFilebaseImageUrl(
                proofsetUrl = GATEWAY_URI,
                imageUrl = null,
            ),
        )
        assertNull(
            FilebaseSocialShareHelper.overviewFilebaseImageUrl(
                proofsetUrl = GATEWAY_URI,
                imageUrl = "   ",
            ),
        )
        assertEquals(
            EXISTING_IMAGE_URL,
            FilebaseSocialShareHelper.overviewFilebaseImageUrl(
                proofsetUrl = GATEWAY_URI,
                imageUrl = EXISTING_IMAGE_URL,
            ),
        )
    }
}
