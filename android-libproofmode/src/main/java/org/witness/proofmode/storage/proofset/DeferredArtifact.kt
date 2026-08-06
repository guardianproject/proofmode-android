package org.witness.proofmode.storage.proofset

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A proof-set member queued for upload, held as a **re-openable source** rather than a
 * materialized buffer.
 *
 * Media leaves can be larger than the whole Dalvik heap — a 700 MB capture cannot fit a 512 MB
 * heap — so nothing on the upload path may hold one as a `ByteArray`. [openStream] is called once
 * per transfer (and again if OkHttp replays the body); [length] comes from a metadata probe, so a
 * caller can set `Content-Length` without touching content.
 *
 * Sidecars (CSV/JSON/PGP) are a few KB and are cheapest in memory: build those with [ofBytes].
 *
 * @property identifier basename of the member (e.g. `"hash123.proof.csv"`)
 * @property contentType MIME type for the transfer, or null when unknown
 * @property length byte length, used verbatim as the transfer's content length
 */
class DeferredArtifact(
    val identifier: String,
    val contentType: String?,
    val length: Long,
    private val opener: () -> InputStream,
) {
    /** Fresh stream over this artifact's content. The caller closes it. */
    fun openStream(): InputStream = opener()

    override fun toString(): String =
        "DeferredArtifact(identifier=$identifier, contentType=$contentType, length=$length)"

    companion object {
        /** In-memory artifact. For sidecars only — never for media. */
        @JvmStatic
        fun ofBytes(identifier: String, data: ByteArray, contentType: String?): DeferredArtifact =
            DeferredArtifact(identifier, contentType, data.size.toLong()) { ByteArrayInputStream(data) }
    }
}
