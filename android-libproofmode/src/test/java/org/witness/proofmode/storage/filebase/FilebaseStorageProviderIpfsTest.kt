package org.witness.proofmode.storage.filebase

import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.storage.proofset.DeferredArtifact

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilebaseStorageProviderIpfsTest {

    @Test
    fun parseCid_emptyName_usesWrapperNotLeafHash() {
        val ndjson = """
            {"Name":"abc123.proof.csv","Hash":"bafyLeafCsv","Size":"100"}
            {"Name":"abc123.jpg","Hash":"bafyLeafJpg","Size":"200"}
            {"Name":"","Hash":"bafyRoot","Size":"300"}
        """.trimIndent()

        assertEquals(
            "bafyRoot",
            FilebaseStorageProvider.parseCid(ndjson, name = ""),
        )
    }

    @Test
    fun parseCid_emptyName_returnsNull_whenNoWrapper() {
        val ndjson = """
            {"Name":"abc123.proof.csv","Hash":"bafyLeafCsv","Size":"100"}
            {"Name":"abc123.jpg","Hash":"bafyLeafJpg","Size":"200"}
        """.trimIndent()

        assertNull(FilebaseStorageProvider.parseCid(ndjson, name = ""))
    }

    @Test
    fun parseCid_returnsNull_forBlankOrNullBody() {
        assertNull(FilebaseStorageProvider.parseCid(null))
        assertNull(FilebaseStorageProvider.parseCid(""))
        assertNull(FilebaseStorageProvider.parseCid("   "))
        assertNull(FilebaseStorageProvider.parseCid(null, name = ""))
        assertNull(FilebaseStorageProvider.parseCid("", name = "photo.jpg"))
    }

    @Test
    fun parseCid_emptyName_ignoresNonEmptyNamesEvenWithoutSlash() {
        // Old buggy heuristic matched every flat leaf via !name.contains("/")
        val ndjson = """
            {"Name":"leaf-only","Hash":"bafyWrong","Size":"1"}
            {"Name":"","Hash":"bafyRoot","Size":"2"}
        """.trimIndent()

        assertEquals("bafyRoot", FilebaseStorageProvider.parseCid(ndjson, name = ""))
    }

    @Test
    fun parseCid_nullName_returnsFirstHash() {
        val ndjson = """
            {"Name":"photo.jpg","Hash":"bafyLeaf","Size":"100"}
            {"Name":"","Hash":"bafyRoot","Size":"200"}
        """.trimIndent()

        assertEquals("bafyLeaf", FilebaseStorageProvider.parseCid(ndjson))
    }

    @Test
    fun parseCid_basename_matchesExactName() {
        val ndjson = """
            {"Name":"photo.jpg","Hash":"bafyMedia"}
            {"Name":"","Hash":"bafyRoot"}
        """.trimIndent()
        assertEquals(
            "bafyMedia",
            FilebaseStorageProvider.parseCid(ndjson, name = "photo.jpg"),
        )
        assertNull(FilebaseStorageProvider.parseCid(ndjson, name = "other.jpg"))
    }

    @Test
    fun buildUnpinUrl_includesPinRmWithArgCid() {
        val url = FilebaseStorageProvider.buildUnpinUrl("bafyTestCid")

        assertTrue(url.startsWith("https://rpc.filebase.io"))
        assertTrue(url.contains("/api/v0/pin/rm"))
        assertTrue(url.contains("arg=bafyTestCid"))
    }

    @Test
    fun unpinIpfsCid_blankToken_returnsFalseWithoutThrowing() {
        val provider = FilebaseStorageProvider(
            accessKey = "a",
            secretKey = "s",
            bucketName = "b",
            ipfsBearerToken = "",
        )

        assertFalse(provider.unpinIpfsCid("bafyCid"))
    }

    @Test
    fun unpinIpfsCid_blankCid_returnsFalseWithoutThrowing() {
        val provider = FilebaseStorageProvider(
            accessKey = "a",
            secretKey = "s",
            bucketName = "b",
            ipfsBearerToken = "tok",
        )

        assertFalse(provider.unpinIpfsCid(""))
        assertFalse(provider.unpinIpfsCid("   "))
    }

    @Test
    fun uploadFileIpfs_blankToken_returnsNullWithoutThrowing() {
        val provider = FilebaseStorageProvider(
            accessKey = "a",
            secretKey = "s",
            bucketName = "b",
            ipfsBearerToken = "",
        )

        assertNull(provider.uploadFileIpfs("photo.jpg", byteArrayOf(1, 2), "image/jpeg"))
    }

    // --- Adapter-level uploadDirectory return-value tests ---
    //
    // `postIpfsAdd` is protected open, allowing a test subclass to inject a fake NDJSON
    // response body. Production logic in `uploadDirectory` — error handling, parseCid,
    // and FilebaseUploadResult construction — runs unchanged.

    @Test
    fun uploadDirectory_returnsResultWithCorrectLeafCidAndDirectoryUri_forKnownNdjson() {
        val ndjson = """
            {"Name":"photo.jpg","Hash":"bafyMediaLeaf","Size":"200"}
            {"Name":"photo.proof.csv","Hash":"bafyLeafCsv","Size":"100"}
            {"Name":"","Hash":"bafyDirRoot","Size":"300"}
        """.trimIndent()

        val provider = FakeHttpFilebaseStorageProvider(fakeCode = 200, fakeBody = ndjson)
        val artifacts = listOf(
            DeferredArtifact.ofBytes("photo.jpg", byteArrayOf(1, 2), "image/jpeg"),
            DeferredArtifact.ofBytes("photo.proof.csv", byteArrayOf(3, 4), "text/csv"),
        )

        val result = provider.uploadDirectory("abc123", artifacts, "photo.jpg", null)

        assertEquals("bafyMediaLeaf", result?.mediaLeafCid)
        assertEquals("https://ipfs.filebase.io/ipfs/bafyDirRoot", result?.directoryUri)
        assertTrue(provider.lastUrl.contains("wrap-with-directory=true"))
        assertTrue(provider.lastUrl.contains("cid-version=1"))
        assertFalse(provider.lastUrl.contains("raw-leaves"))
        assertFalse(provider.lastUrl.contains("chunker"))
    }

    @Test
    fun uploadDirectory_returnsNull_onNon2xxResponse() {
        val provider = FakeHttpFilebaseStorageProvider(fakeCode = 500, fakeBody = "internal error")
        val artifacts = listOf(DeferredArtifact.ofBytes("photo.jpg", byteArrayOf(1), "image/jpeg"))

        assertNull(provider.uploadDirectory("abc123", artifacts, "photo.jpg", null))
    }

    @Test
    fun uploadDirectory_returnsNull_whenTokenIsBlank() {
        val provider = FilebaseStorageProvider(
            accessKey = "a",
            secretKey = "s",
            bucketName = "b",
            ipfsBearerToken = "",
        )
        val artifacts = listOf(DeferredArtifact.ofBytes("photo.jpg", byteArrayOf(1), "image/jpeg"))

        assertNull(provider.uploadDirectory("abc123", artifacts, "photo.jpg", null))
    }

    @Test
    fun uploadFileIpfs_usesCidVersionOnly_withoutWrap() {
        val ndjson = """{"Name":"photo.jpg","Hash":"bafyLeaf","Size":"100"}"""
        val provider = FakeHttpFilebaseStorageProvider(fakeCode = 200, fakeBody = ndjson)

        val uri = provider.uploadFileIpfs("photo.jpg", byteArrayOf(1, 2), "image/jpeg")

        assertEquals("https://ipfs.filebase.io/ipfs/bafyLeaf", uri)
        assertTrue(provider.lastUrl.contains("/api/v0/add"))
        assertTrue(provider.lastUrl.contains("cid-version=1"))
        assertFalse(provider.lastUrl.contains("wrap-with-directory"))
    }
}

/**
 * Test subclass that short-circuits the real OkHttp call in [postIpfsAdd], returning a
 * predetermined status code and body. All other production logic in [uploadDirectory] /
 * [uploadFileIpfs] runs exactly as in production.
 */
private class FakeHttpFilebaseStorageProvider(
    private val fakeCode: Int,
    private val fakeBody: String?,
) : FilebaseStorageProvider(
    accessKey = "a",
    secretKey = "s",
    bucketName = "b",
    ipfsBearerToken = "tok",
) {
    var lastUrl: String = ""
        private set

    override fun postIpfsAdd(url: String, body: RequestBody): Pair<Int, String?> {
        lastUrl = url
        return fakeCode to fakeBody
    }
}
