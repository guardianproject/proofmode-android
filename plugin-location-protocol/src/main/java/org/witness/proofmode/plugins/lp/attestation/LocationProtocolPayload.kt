package org.witness.proofmode.plugins.lp.attestation

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * Represents the Location Protocol payload for EAS attestations.
 *
 * Field names and types match the storacha-integration
 * `org.witness.proofmode.LocationProtocolPayload` data class exactly.
 */
@Keep
@Serializable
data class LocationProtocolPayload(
    val eventTimestamp: Long,           // uint256 — timestamp in milliseconds
    val srs: String,                    // string  — spatial reference system (e.g. "WGS84")
    val locationType: String,           // string  — type of location format (e.g. "geojson")
    val location: String,               // string  — location data (GeoJSON string)
    val recipeType: Array<String>,      // string[] — array of recipe types
    val recipePayload: Array<String>,   // bytes[] — "" baseline, or proof-set rootCid when enriched
    val mediaType: Array<String>,       // string[] — file extension (e.g. "jpg"), not MIME
    val mediaData: Array<String>,       // string[] — SHA-256 baseline, or media leaf CID when enriched
    val memo: String                    // string  — notes / memo
) {
    companion object {
        /**
         * Returns an empty payload with canonical defaults aligned with the
         * storacha-integration semantics.
         */
        fun empty() = LocationProtocolPayload(
            eventTimestamp = 0L,
            srs = "WGS84",
            locationType = "geojson",
            location = "",
            recipeType = emptyArray(),
            recipePayload = emptyArray(),
            mediaType = emptyArray(),
            mediaData = emptyArray(),
            memo = ""
        )
    }

    // Array fields require manual equals/hashCode because Kotlin data classes use
    // referential equality for arrays by default.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LocationProtocolPayload
        return eventTimestamp == other.eventTimestamp &&
            srs == other.srs &&
            locationType == other.locationType &&
            location == other.location &&
            recipeType.contentEquals(other.recipeType) &&
            recipePayload.contentEquals(other.recipePayload) &&
            mediaType.contentEquals(other.mediaType) &&
            mediaData.contentEquals(other.mediaData) &&
            memo == other.memo
    }

    override fun hashCode(): Int {
        var result = eventTimestamp.hashCode()
        result = 31 * result + srs.hashCode()
        result = 31 * result + locationType.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + recipeType.contentHashCode()
        result = 31 * result + recipePayload.contentHashCode()
        result = 31 * result + mediaType.contentHashCode()
        result = 31 * result + mediaData.contentHashCode()
        result = 31 * result + memo.hashCode()
        return result
    }
}
