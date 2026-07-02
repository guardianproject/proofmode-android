package org.witness.proofmode.plugins.lp.attestation

import android.content.ContentResolver
import android.net.Uri
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.util.ProofModeUtil
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * Extracts Location Protocol payloads from ProofMode proof metadata stored by
 * [StorageProvider]. Uses the non-compliant / fallback strategy: if GPS coordinates
 * are absent or 0.0, the payload is still produced with zeroed coordinates rather
 * than returning null — preserving safety boundaries for downstream pipeline steps.
 *
 * Decision note (Phase 2): proof-derived extraction is intentionally kept over
 * direct EXIF extraction for consistency with the existing proof pipeline.
 * Phase 3 may add EXIF-fallback if warranted.
 */
object LocationProtocolHelper {

    /**
     * Builds a [LocationProtocolPayload] from ProofMode proof data for the given [mediaHash].
     *
     * If proof data is not found or GPS fields are absent/zero the payload is still returned
     * with zeroed coordinates (non-compliant / safe-fallback variant). Returns null only when
     * [storageProvider] cannot supply any proof data at all for the given hash.
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
        memo: String = ""
    ): LocationProtocolPayload? {
        return try {
            if (!storageProvider.proofExists(mediaHash)) {
                Timber.w("LP helper: no proof found for hash %s", mediaHash)
                return null
            }

            val proofData = ProofModeUtil.getProofHashMap(storageProvider, mediaHash)

            // --- Coordinates (0.0 fallback when absent/invalid) ---
            val latStr = proofData[ProofModeV1Constants.LOCATION_LATITUDE]
            val lonStr = proofData[ProofModeV1Constants.LOCATION_LONGITUDE]
            val latitude = latStr.nullIfEmpty()?.toDoubleOrNull() ?: 0.0
            val longitude = lonStr.nullIfEmpty()?.toDoubleOrNull() ?: 0.0

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

            // --- Media type ---
            val filePath = proofData[ProofModeV1Constants.FILE_PATH] ?: ""
            val mimeType: String = try {
                contentResolver.getType(mediaUri) ?: extensionMimeType(filePath)
            } catch (e: Exception) {
                Timber.w(e, "LP helper: error determining media type")
                "application/octet-stream"
            }

            // --- File hash (media data) ---
            val fileHash = proofData[ProofModeV1Constants.FILE_HASH_SHA_256] ?: ""

            // --- Memo ---
            val proofMemo = memo.ifEmpty { proofData[ProofModeV1Constants.NOTES] ?: "" }

            LocationProtocolPayload(
                eventTimestamp = eventTimestamp,
                srs = "WGS84",
                locationType = "geojson",
                location = geoJsonLocation,
                recipeType = arrayOf("ProofMode"),
                recipePayload = arrayOf(""),
                mediaType = arrayOf(mimeType),
                mediaData = arrayOf(fileHash),
                memo = proofMemo
            )
        } catch (e: Exception) {
            Timber.e(e, "LP helper: unexpected error building payload for hash %s", mediaHash)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun String?.nullIfEmpty(): String? = if (isNullOrEmpty()) null else this

    private fun extensionMimeType(filePath: String): String {
        return when (filePath.substringAfterLast(".", "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "mp4"         -> "video/mp4"
            "mov"         -> "video/quicktime"
            "mp3"         -> "audio/mpeg"
            "wav"         -> "audio/wav"
            else          -> "application/octet-stream"
        }
    }
}
