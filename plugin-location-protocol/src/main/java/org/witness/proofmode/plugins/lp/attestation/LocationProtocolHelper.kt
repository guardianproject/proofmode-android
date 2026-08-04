package org.witness.proofmode.plugins.lp.attestation

import android.content.ContentResolver
import android.net.Uri
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.util.ProofModeUtil
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

data class BuildPayloadResult(
    val payload: LocationProtocolPayload,
    val captureMimeType: String?,
)

/** Validates coordinates before they are included in a Location Protocol payload. */
object LocationProtocolCoordinateValidator {

    /**
     * Returns true for finite, in-range coordinates except the ambiguous `(0, 0)` pair.
     * A single zero axis is valid for locations on the equator or prime meridian.
     */
    fun isValid(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null ||
            !latitude.isFinite() || !longitude.isFinite()
        ) {
            return false
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return false
        }
        return latitude != 0.0 || longitude != 0.0
    }
}

/**
 * Extracts Location Protocol payloads from ProofMode proof metadata stored by
 * [StorageProvider]. Payload creation requires trustworthy GPS coordinates; missing,
 * malformed, or ambiguous coordinates cause this helper to return null.
 *
 * Decision note (Phase 2): proof-derived extraction is intentionally kept over
 * direct EXIF extraction for consistency with the existing proof pipeline.
 * Phase 3 may add EXIF-fallback if warranted.
 */
object LocationProtocolHelper {

    /**
     * Builds a [LocationProtocolPayload] from ProofMode proof data for the given [mediaHash].
     *
        * Returns null when proof data cannot be supplied or when its GPS coordinates are missing,
    * malformed, out of range, non-finite, or the ambiguous `(0, 0)` pair.
     *
     * @param mediaHash  SHA-256 hash of the source media file
     * @param mediaUri   Original media URI (used to derive MIME type)
     * @param contentResolver  Caller's ContentResolver for MIME type lookup
     * @param storageProvider  ProofMode storage holding proof JSON
     * @param memo        Optional memo string (pass-through to payload)
     */
    fun buildPayload(
        mediaHash: String,
        mediaUri: Uri,
        contentResolver: ContentResolver,
        storageProvider: StorageProvider,
        memo: String = "",
    ): LocationProtocolPayload? = buildPayloadResult(
        mediaHash = mediaHash,
        mediaUri = mediaUri,
        contentResolver = contentResolver,
        storageProvider = storageProvider,
        memo = memo,
    )?.payload

    fun buildPayloadResult(
        mediaHash: String,
        mediaUri: Uri,
        contentResolver: ContentResolver,
        storageProvider: StorageProvider,
        memo: String = "",
    ): BuildPayloadResult? {
        return try {
            if (!storageProvider.proofExists(mediaHash)) {
                Timber.w("LP helper: no proof found for hash %s", mediaHash)
                return null
            }

            val proofData = ProofModeUtil.getProofHashMap(storageProvider, mediaHash)

            // --- Coordinates (required for a trustworthy attestation) ---
            val latStr = proofData[ProofModeV1Constants.LOCATION_LATITUDE]
            val lonStr = proofData[ProofModeV1Constants.LOCATION_LONGITUDE]
            val latitude = latStr.nullIfBlank()?.toDoubleOrNull()
            val longitude = lonStr.nullIfBlank()?.toDoubleOrNull()
            if (!LocationProtocolCoordinateValidator.isValid(latitude, longitude)) {
                Timber.w("LP helper: invalid GPS coordinates for hash %s", mediaHash)
                return null
            }

            val geoJsonLocation = """{"type":"Point","coordinates":[$longitude,$latitude]}"""

            // --- Timestamp ---
            val proofGeneratedStr = proofData[ProofModeV1Constants.PROOF_GENERATED] ?: ""
            val eventTimestamp: Long = if (proofGeneratedStr.isNotEmpty()) {
                try {
                    val sdf = SimpleDateFormat(ProofModeV1Constants.ISO_DATE_TIME_FORMAT, Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    sdf.parse(proofGeneratedStr)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) {
                    Timber.w(e, "LP helper: failed to parse proof timestamp, using current time")
                    System.currentTimeMillis()
                }
            } else {
                System.currentTimeMillis()
            }

            // --- Media type (getType-only; no path fallback) ---
            val captureMimeType: String? = try {
                contentResolver.getType(mediaUri)
            } catch (e: Exception) {
                Timber.w(e, "LP helper: error determining media type via getType")
                null
            }
            val mediaTypeExtension = org.witness.proofmode.plugins.ipfscid.MediaLinkNaming
                .extensionFromMimeType(captureMimeType)

            // --- File hash (media data) ---
            val fileHash = proofData[ProofModeV1Constants.FILE_HASH_SHA_256] ?: ""

            // --- Memo ---
            val proofMemo = memo.ifEmpty { proofData[ProofModeV1Constants.NOTES] ?: "" }

            val payload = LocationProtocolPayload(
                eventTimestamp = eventTimestamp,
                srs = "WGS84",
                locationType = "geojson",
                location = geoJsonLocation,
                recipeType = arrayOf("ProofMode"),
                recipePayload = arrayOf(""),
                mediaType = arrayOf(mediaTypeExtension),
                mediaData = arrayOf(fileHash),
                memo = proofMemo,
            )
            BuildPayloadResult(payload = payload, captureMimeType = captureMimeType)
        } catch (e: Exception) {
            Timber.e(e, "LP helper: unexpected error building payload for hash %s", mediaHash)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun String?.nullIfBlank(): String? = if (isNullOrBlank()) null else this
}
