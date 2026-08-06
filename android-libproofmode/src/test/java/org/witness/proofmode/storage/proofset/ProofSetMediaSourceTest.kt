package org.witness.proofmode.storage.proofset

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

/**
 * The media leaf must be measurable and re-openable without ever being buffered — reading a large
 * capture into a `ByteArray` is what OOM'd the deferred upload path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetMediaSourceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** Content Uri that reports [size] via the SIZE column and throws if anyone reads its bytes. */
    private fun sizedButUnreadableUri(size: Long, reads: AtomicInteger): Uri {
        val uri = Uri.parse("content://org.witness.proofmode.test.sized/media")
        val cursor = RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any>(size)))
        }
        Shadows.shadowOf(context.contentResolver).setCursor(uri, cursor)
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int {
                reads.incrementAndGet()
                throw IOException("media content must not be read to measure it")
            }
        })
        return uri
    }

    @Test
    fun resolve_reportsLengthFromMetadata_withoutReadingContent() {
        val reads = AtomicInteger(0)
        val huge = 733_760_056L // the allocation size from the OOM report
        val source = ProofSetMediaSource.fromUri(context, sizedButUnreadableUri(huge, reads), "video/mp4")

        val resolved = source.resolve()

        assertNotNull(resolved)
        assertEquals(huge, resolved!!.length)
        assertEquals("video/mp4", resolved.mimeType)
        assertEquals(0, reads.get())
    }

    @Test
    fun resolve_fileUri_reportsLengthAndOpensFreshStreamEachTime() {
        val file = File(context.cacheDir, "media-source-test.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val resolved = ProofSetMediaSource.fromUri(context, Uri.fromFile(file), "image/jpeg").resolve()

        assertNotNull(resolved)
        assertEquals(4L, resolved!!.length)
        // OkHttp may replay a request body, so every open must yield the full content again.
        repeat(2) {
            assertEquals(4, resolved.openStream().use { it.readBytes() }.size)
        }
    }

    /**
     * Regression: embedding a C2PA manifest rewrites the media in place, leaving MediaStore's
     * cached SIZE short of the file. Declaring that stale value as Content-Length made OkHttp
     * abort the whole IPFS upload ("expected 4456378 bytes but received 4456448"), so the
     * descriptor of the stream we are about to send has to win over the index.
     */
    @Test
    fun resolve_prefersDescriptorOverStaleSizeColumn() {
        val file = File(context.cacheDir, "media-source-rewritten.jpg")
        file.writeBytes(ByteArray(4_456_448) { it.toByte() })

        val uri = Uri.parse("content://org.witness.proofmode.test.stale/media")
        val staleCursor = RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any>(4_456_378L))) // pre-embed size, 70 bytes short
        }
        Shadows.shadowOf(context.contentResolver).setCursor(uri, staleCursor)
        Shadows.shadowOf(context.contentResolver)
            .registerInputStreamSupplier(uri) { file.inputStream() }

        val resolved = ProofSetMediaSource.fromUri(context, uri, "image/jpeg").resolve()

        assertNotNull(resolved)
        assertEquals(file.length(), resolved!!.length)
        // The promise the transfer makes must match the bytes the transfer sends.
        assertEquals(resolved.length, resolved.openStream().use { it.readBytes().size.toLong() })
    }

    @Test
    fun resolve_returnsNull_whenMediaIsMissing() {
        val missing = Uri.parse("content://org.witness.proofmode.test.missing/media")
        assertNull(ProofSetMediaSource.fromUri(context, missing, "image/jpeg").resolve())
    }

    @Test
    fun resolve_returnsNull_whenProviderHasNothingStashed() {
        assertNull(ProofSetMediaSource.fromUriProvider(context) { null }.resolve())
    }

    @Test
    fun resolve_returnsNull_forEmptyMedia() {
        val empty = File(context.cacheDir, "media-source-empty.jpg")
        empty.writeBytes(ByteArray(0))
        assertNull(ProofSetMediaSource.fromUri(context, Uri.fromFile(empty), "image/jpeg").resolve())
    }

    @Test
    fun resolve_followsProviderToTheCurrentUri() {
        val first = File(context.cacheDir, "media-source-first.jpg")
        first.writeBytes(byteArrayOf(1))
        val second = File(context.cacheDir, "media-source-second.jpg")
        second.writeBytes(byteArrayOf(1, 2, 3))

        var current = first
        val source = ProofSetMediaSource.fromUriProvider(context) { Uri.fromFile(current) to "image/jpeg" }

        assertEquals(1L, source.resolve()!!.length)
        current = second
        assertEquals(3L, source.resolve()!!.length)
    }
}
