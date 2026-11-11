package org.witness.proofmode.importing

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.contentauth.c2pa.C2PA
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Screens a photo or video before ProofMode will import (and C2PA-notarize) it. A file is
 * rejected if it:
 *  1. carries C2PA or XMP provenance saying it is synthetic / AI generated, or a broken C2PA manifest;
 *  2. lacks the EXIF a camera writes into an unedited photo, or names editing/AI software
 *     (videos: lacks basic recording metadata);
 *  3. has an aspect ratio no camera produces.
 *
 * These are heuristics: they stop casual misuse (AI images, screenshots, edited files), not a
 * determined forger who rewrites metadata.
 */
object ImportMediaValidator {

    enum class Reason {
        /** C2PA/XMP digitalSourceType marks the media as synthetic or AI generated. */
        SYNTHETIC_SOURCE,
        /** A C2PA manifest is present but fails validation (tampered or corrupt). */
        INVALID_C2PA,
        /** Missing camera make/model, capture time, or exposure data. */
        MISSING_CAMERA_EXIF,
        /** EXIF Software names an editor or generator. */
        EDITING_SOFTWARE,
        /** Video lacks a video track, duration or recording date. */
        MISSING_VIDEO_METADATA,
        /** Width:height doesn't match a camera format. */
        UNUSUAL_ASPECT_RATIO,
        /** Not a photo/video format a camera records. */
        UNSUPPORTED_TYPE,
        /** File could not be read. */
        UNREADABLE,
    }

    data class Result(val reasons: List<Reason>) {
        val accepted: Boolean get() = reasons.isEmpty()
    }

    /** IPTC digital source types that mean "not a straight camera capture". */
    internal val SYNTHETIC_SOURCE_TYPES = setOf(
        "trainedalgorithmicmedia",
        "compositewithtrainedalgorithmicmedia",
        "algorithmicmedia",
        "algorithmicallyenhanced",
        "compositesynthetic",
        "digitalcreation",
        "digitalart",
        "virtualrecording",
        "datadrivenmedia",
    )

    /** Lowercase substrings of EXIF Software values from editors and generators. */
    internal val EDITING_SOFTWARE = listOf(
        "photoshop", "lightroom", "gimp", "snapseed", "affinity", "pixelmator", "picsart",
        "facetune", "canva", "luminar", "capture one", "darktable", "paint.net",
        "midjourney", "stable diffusion", "dall-e", "dall·e", "firefly", "openai", "chatgpt",
        "gemini", "imagen", "leonardo", "ideogram", "flux", "comfyui", "automatic1111",
    )

    /** Long side / short side of formats cameras and phones record. */
    internal val PHOTO_RATIOS = listOf(1.0, 5.0 / 4, 4.0 / 3, 3.0 / 2, 16.0 / 9, 2.0, 19.5 / 9, 20.0 / 9, 21.0 / 9)
    internal val VIDEO_RATIOS = listOf(1.0, 4.0 / 3, 16.0 / 9, 2.0, 20.0 / 9, 21.0 / 9)
    internal const val RATIO_TOLERANCE = 0.015

    private val PHOTO_TYPES = setOf("image/jpeg", "image/heic", "image/heif", "image/webp", "image/x-adobe-dng", "image/dng")
    private val VIDEO_TYPES = setOf("video/mp4", "video/3gpp", "video/quicktime")

    /** Bytes scanned at the start of the file for embedded XMP. */
    private const val XMP_SCAN_BYTES = 512 * 1024

    private val SOURCE_TYPE_REGEX =
        Regex("""digitalsourcetype(?:/|"\s*:\s*")([A-Za-z]+)""", RegexOption.IGNORE_CASE)

    /** Validates a content URI by copying it to a temp file (C2PA and EXIF readers need a path). */
    fun validate(context: Context, uri: Uri, mimeType: String?): Result {
        val type = mimeType ?: context.contentResolver.getType(uri)
        val tmp = File.createTempFile("import-check", null, context.cacheDir)
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
                true
            } ?: false
            if (copied) validate(tmp, type) else Result(listOf(Reason.UNREADABLE))
        } catch (e: Exception) {
            Timber.w(e, "Could not read %s for import validation", uri)
            Result(listOf(Reason.UNREADABLE))
        } finally {
            tmp.delete()
        }
    }

    fun validate(file: File, mimeType: String?): Result {
        if (!file.canRead()) return Result(listOf(Reason.UNREADABLE))
        val type = mimeType?.lowercase()
        val isPhoto = type in PHOTO_TYPES
        val isVideo = type in VIDEO_TYPES
        if (!isPhoto && !isVideo) return Result(listOf(Reason.UNSUPPORTED_TYPE))

        val reasons = linkedSetOf<Reason>()
        reasons += checkC2pa(file)
        if (findSyntheticSourceTypes(readHead(file)).isNotEmpty()) reasons += Reason.SYNTHETIC_SOURCE

        val dims = if (isPhoto) {
            reasons += checkPhotoExif(file)
            photoDimensions(file)
        } else {
            val (videoReasons, videoDims) = checkVideo(file)
            reasons += videoReasons
            videoDims
        }
        if (dims == null || !matchesCameraAspectRatio(dims.first, dims.second, isVideo)) {
            reasons += Reason.UNUSUAL_ASPECT_RATIO
        }

        val result = Result(reasons.toList())
        Timber.d("Import validation for %s (%s): %s", file.name, type, result.reasons)
        return result
    }

    // --- 1. Provenance --------------------------------------------------------------------

    private fun checkC2pa(file: File): List<Reason> {
        val manifest = try {
            C2PA.readFile(file.absolutePath)
        } catch (e: Throwable) {
            return emptyList() // no manifest: nothing to check
        }
        val reasons = mutableListOf<Reason>()
        if (findSyntheticSourceTypes(manifest).isNotEmpty()) reasons += Reason.SYNTHETIC_SOURCE
        val state = try {
            JSONObject(manifest).optString("validation_state")
        } catch (e: Exception) {
            ""
        }
        if (state.equals("invalid", ignoreCase = true)) reasons += Reason.INVALID_C2PA
        return reasons
    }

    /** IPTC source-type names in [text] (C2PA JSON or XMP) that mark media as synthetic. */
    internal fun findSyntheticSourceTypes(text: String): Set<String> =
        SOURCE_TYPE_REGEX.findAll(text)
            .map { it.groupValues[1].lowercase() }
            .filter { it in SYNTHETIC_SOURCE_TYPES }
            .toSet()

    private fun readHead(file: File): String = try {
        file.inputStream().use { input ->
            val buf = ByteArray(min(file.length(), XMP_SCAN_BYTES.toLong()).toInt())
            val n = input.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.ISO_8859_1)
        }
    } catch (e: Exception) {
        ""
    }

    // --- 2. Camera metadata ---------------------------------------------------------------

    private fun checkPhotoExif(file: File): List<Reason> {
        val exif = try {
            ExifInterface(file)
        } catch (e: Exception) {
            return listOf(Reason.MISSING_CAMERA_EXIF)
        }
        return photoExifReasons { tag -> exif.getAttribute(tag) }
    }

    /** [tag] looks up an EXIF attribute by its ExifInterface tag name. */
    internal fun photoExifReasons(tag: (String) -> String?): List<Reason> {
        fun has(name: String) = !tag(name).isNullOrBlank()
        val reasons = mutableListOf<Reason>()

        val hasCamera = has(ExifInterface.TAG_MAKE) && has(ExifInterface.TAG_MODEL)
        val hasCaptureTime = has(ExifInterface.TAG_DATETIME_ORIGINAL) || has(ExifInterface.TAG_DATETIME)
        val hasExposure = listOf(
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
        ).any(::has)
        if (!hasCamera || !hasCaptureTime || !hasExposure) reasons += Reason.MISSING_CAMERA_EXIF

        if (isEditingSoftware(tag(ExifInterface.TAG_SOFTWARE))) reasons += Reason.EDITING_SOFTWARE
        return reasons
    }

    internal fun isEditingSoftware(software: String?): Boolean {
        val s = software?.lowercase() ?: return false
        return EDITING_SOFTWARE.any { it in s }
    }

    private fun photoDimensions(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) return opts.outWidth to opts.outHeight
        // BitmapFactory can't decode some formats (e.g. DNG); fall back to EXIF.
        return try {
            val exif = ExifInterface(file)
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) w to h else null
        } catch (e: Exception) {
            null
        }
    }

    private fun checkVideo(file: File): Pair<List<Reason>, Pair<Int, Int>?> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            fun key(k: Int) = mmr.extractMetadata(k)
            val hasVideo = key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val hasDate = !key(MediaMetadataRetriever.METADATA_KEY_DATE).isNullOrBlank()
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val reasons = if (hasVideo && duration > 0 && hasDate) emptyList() else listOf(Reason.MISSING_VIDEO_METADATA)
            reasons to (if (w > 0 && h > 0) w to h else null)
        } catch (e: Exception) {
            listOf(Reason.UNREADABLE) to null
        } finally {
            try { mmr.release() } catch (ignored: Exception) {}
        }
    }

    // --- 3. Aspect ratio ------------------------------------------------------------------

    /** Orientation-agnostic: compares long side / short side against known camera formats. */
    internal fun matchesCameraAspectRatio(width: Int, height: Int, isVideo: Boolean): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = max(width, height).toDouble() / min(width, height)
        val allowed = if (isVideo) VIDEO_RATIOS else PHOTO_RATIOS
        return allowed.any { abs(ratio - it) / it <= RATIO_TOLERANCE }
    }
}
