package org.witness.proofmode

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Size
import android.webkit.MimeTypeMap
import java.io.File


/**
 * Get a thumbnail for a video.
 *
 * @param context The application context.
 * @param videoUri The Uri of the video.
 * @return A Bitmap thumbnail.
 */
fun getVideoThumbnail(context: Context, videoUri: Uri): Bitmap {

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return context.contentResolver.loadThumbnail(videoUri, Size(840, 640), null)
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
            R.drawable.proofmodegrey
        );
    }


}

fun getRealUri(contentUri: Uri?): Uri? {
    val unusablePath = contentUri!!.path
    val startIndex = unusablePath!!.indexOf("external/")
    val endIndex = unusablePath.indexOf("/ACTUAL")
    return if (startIndex != -1 && endIndex != -1) {
        val embeddedPath = unusablePath.substring(startIndex, endIndex)
        val builder = contentUri.buildUpon()
        builder.path(embeddedPath)
        builder.authority("media")
        builder.build()
    } else contentUri
}

fun getFileNameFromUri(contentResolver: ContentResolver, uri: Uri?): String? {
    val projection = arrayOfNulls<String>(2)
    val mimeType = contentResolver.getType(uri!!)
    val mimeTypeMap = MimeTypeMap.getSingleton()
    val fileExt = mimeTypeMap.getExtensionFromMimeType(mimeType)
    if (mimeType != null) {
        if (mimeType.startsWith("image")) {
            projection[0] = MediaStore.Images.Media.DATA
            projection[1] = MediaStore.Images.Media.DISPLAY_NAME
        } else if (mimeType.startsWith("video")) {
            projection[0] = MediaStore.Video.Media.DATA
            projection[1] = MediaStore.Video.Media.DISPLAY_NAME
        } else if (mimeType.startsWith("audio")) {
            projection[0] = MediaStore.Audio.Media.DATA
            projection[1] = MediaStore.Audio.Media.DISPLAY_NAME
        }
    } else {
        projection[0] = MediaStore.Images.Media.DATA
        projection[1] = MediaStore.Images.Media.DISPLAY_NAME
    }
    val cursor = contentResolver.query(getRealUri(uri)!!, projection, null, null, null)
    val result = false

    //default name with file extension
    var fileName = uri.lastPathSegment
    if (fileExt != null && fileName!!.indexOf(".") == -1) fileName += ".$fileExt"
    if (cursor != null) {
        if (cursor.count > 0) {
            cursor.moveToFirst()
            try {
                var columnIndex = cursor.getColumnIndexOrThrow(projection[0])
                val path = cursor.getString(columnIndex)
                if (path != null) {
                    val fileMedia = File(path)
                    if (fileMedia.exists()) fileName = fileMedia.name
                }
                if (TextUtils.isEmpty(fileName)) {
                    columnIndex = cursor.getColumnIndexOrThrow(projection[1])
                    fileName = cursor.getString(columnIndex)
                }
            } catch (_: IllegalArgumentException) {
            }
        }
        cursor.close()
    }
    if (TextUtils.isEmpty(fileName)) fileName = uri.lastPathSegment
    return fileName
}

enum class MediaType {
    IMAGE, VIDEO, UNKNOWN
}

/**
 * Gets mime types for File Uris
 */

fun getMediaTypeFromFileUri(uri: Uri): MediaType {
    val file = File(uri.path ?: return MediaType.UNKNOWN)
    val extension = file.extension.lowercase()

    if (extension.isEmpty()) return MediaType.UNKNOWN

    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension)

    return when {
        mimeType == null -> MediaType.UNKNOWN
        mimeType.startsWith("image/") -> MediaType.IMAGE
        mimeType.startsWith("video/") -> MediaType.VIDEO
        else -> MediaType.UNKNOWN
    }
}