package org.witness.proofmode.camera.utils

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.witness.proofmode.camera.R
import org.witness.proofmode.camera.adapter.Media
import java.io.File

@OptIn(ExperimentalCamera2Interop::class)
fun getSupportedQualities(
    cameraSelector: CameraSelector,
    cameraProvider: CameraProvider): List<Quality> {
    val cameraMetadataLens = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
    val cameraInfo = cameraProvider.availableCameraInfos.filter {
        Camera2CameraInfo
            .from(it)
            .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == cameraMetadataLens
    }

    val videoCapabilities = Recorder.getVideoCapabilities(cameraInfo[0])
    val supportedDynamicRanges = videoCapabilities.supportedDynamicRanges
    val dynamicRange = if (supportedDynamicRanges.contains(DynamicRange.HDR10_10_BIT)) DynamicRange.HDR10_10_BIT else DynamicRange.SDR
    return videoCapabilities.getSupportedQualities(dynamicRange)
}

fun isUltraHdrSupported(cameraSelector: CameraSelector, cameraProvider: CameraProvider): Boolean {
    val cameraInfo = cameraProvider.getCameraInfo(cameraSelector)
    return ImageCapture.getImageCaptureCapabilities(cameraInfo)
        .supportedOutputFormats
        .contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)

}

fun Quality.getName():String {
    return when (this) {
        Quality.UHD -> "4K(UHD)"
        Quality.FHD -> "1080p(FHD)"
        Quality.HD -> "720p(HD)"
        Quality.SD -> "480p(SD)"
        else -> this.toString()
    }
}

fun getQualityFromName(name: String): Quality {
    return when (name) {
        "4K(UHD)" -> Quality.UHD
        "1080p(FHD)" -> Quality.FHD
        "720p(HD)" -> Quality.HD
        "480p(SD)" -> Quality.SD
        else -> throw IllegalArgumentException("Invalid quality name: $name")
    }
}

fun Quality.toIconRes(): Int {
    return when (this) {
        Quality.UHD -> R.drawable.ic_4k
        Quality.FHD -> R.drawable.ic_fhd
        Quality.HD -> R.drawable.ic_hd
        Quality.SD -> R.drawable.ic_sd
        else -> throw IllegalStateException("Icon resource not found")
    }
}

fun flashModeToIconRes(flashMode: Int): ImageVector {
    return when (flashMode) {
        ImageCapture.FLASH_MODE_OFF -> Icons.Filled.FlashOff
        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
        ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
        else -> Icons.Filled.FlashOff
    }


}

fun getMediaFlow(context: Context, outputDirectory: String): Flow<List<Media>> = flow {
    emit(emptyList())
    val media = getMedia(context,outputDirectory)
    emit(media)
}.flowOn(Dispatchers.IO)


suspend fun getMedia(context: Context, outputDirectory: String): List<Media> = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        getMediaQPlus(context, outputDirectory)
    } else {
        getMediaQMinus(context, outputDirectory)
    }
}.reversed()

private fun getMediaQPlus(context: Context, outputDirectory: String): List<Media> {
    val items = mutableListOf<Media>()
    val contentResolver = context.applicationContext.contentResolver

    contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_TAKEN,
        ),
        null,
        null,
        "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val path = cursor.getString(pathColumn)
            val date = cursor.getLong(dateColumn)

            val contentUri: Uri =
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

            if (path == outputDirectory) {
                items.add(Media(contentUri, true, date))
            }
        }
    }

    contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
        ),
        null,
        null,
        "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val path = cursor.getString(pathColumn)
            val date = cursor.getLong(dateColumn)

            val contentUri: Uri =
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

            if (path == outputDirectory) {
                items.add(Media(contentUri, false, date))
            }
        }
    }
    items.sortBy { it.date }

    return items
}

private fun getMediaQMinus(context: Context, outputDirectory: String): List<Media> {
    val items = mutableListOf<Media>()

    File(outputDirectory).listFiles()?.forEach {
        val authority = context.applicationContext.packageName + ".provider"
        val mediaUri = FileProvider.getUriForFile(context, authority, it)
        items.add(Media(mediaUri, it.extension == "mp4", it.lastModified()))
    }

    return items
}

/**
 * Get a thumbnail for a video.
 *
 * @param context The application context.
 * @param videoUri The Uri of the video The uri needs to be a content uri to work.
 * @return A Bitmap thumbnail.
 */
fun getVideoThumbnail(context: Context, videoUri: Uri,size: ThumbSize = ThumbSize.SMALL): Bitmap {
    val thumbBounds = when (size) {
        ThumbSize.SMALL -> Size(840, 640)
        ThumbSize.LARGE -> Size(1280, 720)
    }

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return context.contentResolver.loadThumbnail(videoUri, thumbBounds, null)
        }
        return MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver,
            ContentUris.parseId(videoUri),
            MediaStore.Video.Thumbnails.FULL_SCREEN_KIND,
            null
        )
    } catch (e: Exception) {

        //couldn't load thumbnail for some reason
        return BitmapFactory.decodeResource(
            context.resources,
            R.drawable.ic_no_picture
        );
    }


}

enum class ThumbSize {
    SMALL,
    LARGE
}

