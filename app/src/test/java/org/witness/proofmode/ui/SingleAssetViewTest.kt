package org.witness.proofmode.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.witness.proofmode.R
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class SingleAssetViewTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowToast.reset()
        clearFileProviderPathCache()
    }

    // FileProvider caches its root map per authority for the whole JVM, but Robolectric hands each
    // test a new filesDir, so a cached map from an earlier test would point at a deleted directory.
    private fun clearFileProviderPathCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    private fun clipText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun clipLabel(): CharSequence? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.description?.label
    }

    @Test
    fun copyTextToClipboard_putsTextAndLabelOnClipboard() {
        copyTextToClipboard(context, "the label", "  text, verbatim\n")

        assertEquals("  text, verbatim\n", clipText())
        assertEquals("the label", clipLabel().toString())
    }

    @Test
    fun copyTextToClipboard_toastsOnlyOnApi32AndBelow() {
        // A different text from the 33 call, so clipText() below proves the 33 call set the clip itself.
        copyTextToClipboard(context, "label", "text32", sdkInt = 32)

        assertEquals(context.getString(R.string.artifact_copied), ShadowToast.getTextOfLatestToast())

        ShadowToast.reset()
        copyTextToClipboard(context, "label", "text", sdkInt = 33)

        assertNull(ShadowToast.getLatestToast())
        assertEquals("text", clipText())
    }

    // BOM, CRLF, odd whitespace and a stray 0xC3 that is not valid UTF-8: a String round trip
    // or formatArtifactJson would rewrite every one of these.
    private val awkwardBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        "{ \"a\" :\t1,\r\n  \"b\":2 }".toByteArray(Charsets.UTF_8) +
        byteArrayOf(0xC3.toByte(), 0x28)

    @Test
    fun copyStoredArtifact_writesStoredBytesUnchanged() {
        val input = ClosableInput(awkwardBytes)
        val storage = FakeStorageProvider(input = input)
        val output = ClosableOutput()

        val saved = copyStoredArtifact(storage, "hash", "hash.lp.offchain.json") { output }

        assertTrue(saved)
        assertArrayEquals(awkwardBytes, output.toByteArray())
        assertTrue(input.closed)
        assertTrue(output.closed)
        assertEquals(listOf("hash" to "hash.lp.offchain.json"), storage.requests)
    }

    @Test
    fun copyStoredArtifact_returnsFalseWhenSidecarMissing() {
        var openOutputCalls = 0

        val saved = copyStoredArtifact(FakeStorageProvider(input = null), "hash", "id") {
            openOutputCalls++
            ClosableOutput()
        }

        assertFalse(saved)
        assertEquals(0, openOutputCalls)
    }

    @Test
    fun copyStoredArtifact_returnsFalseWhenOutputStreamNull() {
        val input = ClosableInput(awkwardBytes)

        val saved = copyStoredArtifact(FakeStorageProvider(input = input), "hash", "id") { null }

        assertFalse(saved)
        assertTrue(input.closed)
    }

    private class StorageFailureCase(
        val label: String,
        val storage: FakeStorageProvider,
        val input: ClosableInput?,
        val output: ClosableOutput?,
    )

    @Test
    fun copyStoredArtifact_returnsFalseWhenStorageFails() {
        val readInput = ClosableInput(awkwardBytes, failOnRead = true)
        val readOutput = ClosableOutput()
        val writeInput = ClosableInput(awkwardBytes)
        val writeOutput = ClosableOutput(failOnWrite = true)
        // throwOnOpen has no stream to close: getInputStream throws before openOutput runs.
        listOf(
            StorageFailureCase("failOnRead", FakeStorageProvider(input = readInput), readInput, readOutput),
            StorageFailureCase("failOnWrite", FakeStorageProvider(input = writeInput), writeInput, writeOutput),
            StorageFailureCase("throwOnOpen", FakeStorageProvider(throwOnOpen = true), null, null),
        ).forEach { case ->
            val saved = copyStoredArtifact(case.storage, "hash", "id") { case.output ?: ClosableOutput() }

            assertFalse(case.label, saved)
            case.input?.let { assertTrue("${case.label}: input closed", it.closed) }
            case.output?.let { assertTrue("${case.label}: output closed", it.closed) }
        }
    }

    @Test
    fun artifactSummaryLine_joinsChainAndHint() {
        assertEquals("Arbitrum One — Tap to view", artifactSummaryLine("Arbitrum One", "Tap to view"))
        assertEquals("Tap to view", artifactSummaryLine(null, "Tap to view"))
    }

    private fun storageWithSidecar(hash: String, identifier: String, bytes: ByteArray): DefaultStorageProvider {
        val storage = DefaultStorageProvider(context)
        File(storage.getHashStorageDir(hash), identifier).writeBytes(bytes)
        return storage
    }

    @Test
    fun artifactShareUri_returnsUriReadingStoredBytes() {
        val hash = "abc123"
        val identifier = "$hash.lp.offchain.json"
        val storage = storageWithSidecar(hash, identifier, awkwardBytes)

        val uri = artifactShareUri(context, storage, hash, identifier)

        val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(awkwardBytes, read)
        assertEquals(identifier, uri.lastPathSegment)
    }

    private class ClosableInput(bytes: ByteArray, private val failOnRead: Boolean = false) :
        ByteArrayInputStream(bytes) {
        var closed = false
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (failOnRead) throw IOException("read failed")
            return super.read(b, off, len)
        }
        override fun close() {
            closed = true
            super.close()
        }
    }

    private class ClosableOutput(private val failOnWrite: Boolean = false) : ByteArrayOutputStream() {
        var closed = false
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failOnWrite) throw IOException("write failed")
            super.write(b, off, len)
        }
        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeStorageProvider(
        private val input: InputStream? = null,
        private val throwOnOpen: Boolean = false,
    ) : StorageProvider {
        val requests = mutableListOf<Pair<String?, String?>>()

        override fun getInputStream(hash: String?, identifier: String?): InputStream? {
            requests += hash to identifier
            if (throwOnOpen) throw IOException("open failed")
            return input
        }

        override fun saveStream(hash: String?, identifier: String?, stream: InputStream?, listener: StorageListener?) = Unit
        override fun saveBytes(hash: String?, identifier: String?, data: ByteArray?, listener: StorageListener?) = Unit
        override fun saveText(hash: String?, identifier: String?, data: String?, listener: StorageListener?) = Unit
        override fun replaceText(hash: String?, identifier: String?, data: String?, listener: StorageListener?) = Unit
        override fun proofExists(hash: String?): Boolean = false
        override fun proofIdentifierExists(hash: String?, identifier: String?): Boolean = false
        override fun getProofSet(hash: String?): ArrayList<Uri> = arrayListOf()
        override fun getProofItem(uri: Uri?): InputStream? = null
    }
}
