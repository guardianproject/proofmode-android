package org.witness.proofmode.storage.proofset

/**
 * Represents a proof artifact that has been deferred for later batch upload.
 * 
 * @property identifier The unique identifier for this artifact (e.g., "hash123.proof.csv")
 * @property data The raw byte data of the artifact
 * @property contentType Optional MIME type for the artifact (e.g., "text/csv", "image/jpeg")
 */
data class DeferredArtifact(
    val identifier: String,
    val data: ByteArray,
    val contentType: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DeferredArtifact

        if (identifier != other.identifier) return false
        if (!data.contentEquals(other.data)) return false
        if (contentType != other.contentType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identifier.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }
}
