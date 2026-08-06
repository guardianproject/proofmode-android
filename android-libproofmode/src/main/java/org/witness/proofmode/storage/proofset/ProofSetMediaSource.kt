package org.witness.proofmode.storage.proofset

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The media leaf of a proof set, exposed as a handle that can be opened repeatedly and measured
 * without being read.
 *
 * Media bytes are never materialized. Reading a capture into a `ByteArray` OOM'd the process
 * outright on large video — a 700 MB file cannot be allocated on a 512 MB heap, and the deferred
 * flush attempted exactly that on every sidecar save. Everything that needs media now goes through
 * [resolve].
 */
fun interface ProofSetMediaSource {

    /**
     * Snapshot the media for one transfer, or `null` when it is missing, unreadable, or empty.
     *
     * A non-null result is the INCLUDE_MEDIA availability gate: it means a stream can be opened and
     * its length is known. Resolving performs metadata probes only.
     */
    fun resolve(): ResolvedMedia?

    companion object {
        private const val TAG = "ProofSetMediaSource"

        /**
         * Media behind a content/file [Uri] that is looked up through [provider] on every
         * [resolve], so a rebind between flushes takes effect. Returns `null` while the provider
         * has nothing stashed.
         */
        @JvmStatic
        fun fromUriProvider(
            context: Context,
            provider: () -> Pair<Uri, String?>?,
        ): ProofSetMediaSource = UriMediaSource(context.applicationContext, provider)

        @JvmStatic
        fun fromUri(context: Context, uri: Uri, mimeType: String?): ProofSetMediaSource =
            fromUriProvider(context) { uri to mimeType }

        /** In-memory media. For small fixtures and callers that already hold the bytes. */
        @JvmStatic
        fun ofBytes(data: ByteArray, mimeType: String?): ProofSetMediaSource =
            ProofSetMediaSource {
                if (data.isEmpty()) {
                    null
                } else {
                    ResolvedMedia(mimeType, data.size.toLong()) { ByteArrayInputStream(data) }
                }
            }

        /**
         * Byte length of [uri] without reading it, or `-1` when no probe answers.
         *
         * The length becomes the transfer's `Content-Length`, so it must describe the exact bytes
         * [ProofSetMediaSource.resolve]'s stream will yield — a promise OkHttp enforces and fails
         * the upload over. That rules out asking the provider's index first:
         * `MediaStore.SIZE` is a value cached at scan time, and ProofMode rewrites media in place
         * to embed a C2PA manifest, which leaves the column short by the size of the manifest.
         *
         * So descriptors first (they stat the real file), then the index, then — only for
         * providers that answer neither — a constant-memory count off the stream.
         */
        internal fun probeLength(context: Context, uri: Uri): Long {
            if (uri.scheme == null || uri.scheme == "file") {
                val path = uri.path ?: return -1
                val length = File(path).length()
                return if (length > 0) length else -1
            }

            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    // A declared length means the provider is exposing a sub-range and the stream
                    // will honour it; otherwise `length` falls through to the fd's stat size.
                    val declared = afd.declaredLength
                    if (declared > 0) return declared
                    val length = afd.length
                    if (length > 0) return length
                }
            } catch (e: Exception) {
                Log.d(TAG, "Asset file descriptor unavailable for $uri", e)
            }

            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    // Stat the very descriptor the upload will read, without reading it.
                    if (stream is FileInputStream) {
                        val length = stream.channel.size()
                        if (length > 0) return length
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Stream descriptor unavailable for $uri", e)
            }

            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                            val size = cursor.getLong(column)
                            if (size > 0) return size
                        }
                    }
            } catch (e: Exception) {
                Log.d(TAG, "SIZE column unavailable for $uri", e)
            }

            return try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                    if (total > 0) total else -1
                } ?: -1
            } catch (e: Exception) {
                Log.w(TAG, "Failed to measure media at $uri", e)
                -1
            }
        }
    }
}

/**
 * One resolved media handle: a known [length] plus a stream that can be opened as many times as the
 * transfer needs.
 */
class ResolvedMedia(
    val mimeType: String?,
    val length: Long,
    private val opener: () -> InputStream,
) {
    /** Fresh stream over the media. The caller closes it. */
    fun openStream(): InputStream = opener()
}

private class UriMediaSource(
    private val context: Context,
    private val provider: () -> Pair<Uri, String?>?,
) : ProofSetMediaSource {

    /**
     * Measured fresh on every resolve, never cached. The upload path resolves last while holding
     * the per-hash mutex, so re-probing is what keeps the declared length describing the file as
     * it is at transfer time rather than as it was at the first flush attempt.
     */
    override fun resolve(): ResolvedMedia? {
        val (uri, mimeType) = provider() ?: return null
        val length = ProofSetMediaSource.probeLength(context, uri)
        if (length <= 0) return null
        return ResolvedMedia(mimeType, length) {
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("Media no longer readable: $uri")
        }
    }
}
