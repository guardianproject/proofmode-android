package org.witness.proofmode.storage.proofset

/** MIME mapping for proof-set member identifiers. */
object ProofSetContentTypes {
    fun contentTypeFor(identifier: String): String = when {
        identifier.endsWith(".jpg") || identifier.endsWith(".jpeg") -> "image/jpeg"
        identifier.endsWith(".png") -> "image/png"
        identifier.endsWith(".mp4") -> "video/mp4"
        identifier.endsWith(".csv") -> "text/csv"
        identifier.endsWith(".asc") || identifier.endsWith(".gpg") -> "application/pgp-signature"
        identifier.endsWith(".zip") -> "application/zip"
        identifier.endsWith(".json") -> "application/json"
        else -> "application/octet-stream"
    }
}
