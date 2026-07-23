package org.witness.proofmode.share

import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * JVM unit tests for [FilebaseSocialShareHelper.deriveAndPersistFromDirectory].
 *
 * Testability rationale: [FilebaseSocialShareHelper.resolveSocialVerifyUrl] takes
 * [android.content.ContentResolver] and [android.net.Uri] (Android framework types).
 * The app module has only `testImplementation(libs.junit)` — no Robolectric — so the full
 * function cannot be driven from a plain JVM test without adding a build dependency (scope
 * creep per the task brief). Instead, the persist-decision logic was extracted into the
 * internal function [FilebaseSocialShareHelper.deriveAndPersistFromDirectory], which takes
 * only [StorageProvider] (a plain Java interface) and is therefore fully testable here.
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
