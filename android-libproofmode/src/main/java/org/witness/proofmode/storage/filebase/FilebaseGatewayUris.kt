package org.witness.proofmode.storage.filebase

/**
 * Filebase IPFS gateway URI builders and parsers.
 *
 * IPFS directory-root URLs live only in `{hash}.filebase.ipfs.uri` (never S3).
 * Media/share URLs live in `{hash}.filebase.image.uri` (leaf form preferred).
 */
object FilebaseGatewayUris {
    const val GATEWAY_BASE = "https://ipfs.filebase.io/ipfs"

    fun buildProofsetUri(directoryCid: String): String = "$GATEWAY_BASE/$directoryCid"

    fun buildImageUriUnderDirectory(directoryCid: String, mediaBasename: String): String =
        "$GATEWAY_BASE/$directoryCid/$mediaBasename"

    fun buildLeafImageUri(leafCid: String): String = "$GATEWAY_BASE/$leafCid"

    /**
     * Last non-blank line of sidecar text. [DefaultStorageProvider.saveText] historically
     * appended; multi-line sidecar files need the latest URI for unpin/derive.
     */
    fun latestNonBlankLine(text: String): String =
        text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: text.trim()

    /**
     * Segment after `/ipfs/` before `/`, `?`, `#`, or whitespace.
     * When [uri] contains multiple gateway lines, uses the latest non-blank line.
     */
    fun parseGatewayRootCid(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val line = latestNonBlankLine(uri)
        val marker = "/ipfs/"
        val idx = line.indexOf(marker)
        if (idx < 0) return null
        val after = line.substring(idx + marker.length)
        val end = after.indexOfFirst { it == '/' || it == '?' || it == '#' || it.isWhitespace() }
        val cid = if (end < 0) after else after.substring(0, end)
        return cid.takeIf { it.isNotBlank() }
    }
}
