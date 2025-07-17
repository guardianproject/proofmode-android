package org.witness.proofmode.c2pa


import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.provider.DocumentsContractCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.round
import kotlin.math.roundToInt

object ThumbUtils {

    suspend fun Context.getThumbBitmapForMedia(mediaUri: Uri): Bitmap? = runCatching {
        getDefaultThumbOrThrow(this@getThumbBitmapForMedia, mediaUri)
    }.getOrElse {
        runCatching {
            withContext(Dispatchers.IO) {
                when (resolveType(contentResolver.getType(mediaUri))) {
                    Type.IMAGE -> {
                        generateImageThumb(this@getThumbBitmapForMedia, mediaUri)
                    }
                    Type.VIDEO -> {
                        generateVideoThumb(this@getThumbBitmapForMedia, mediaUri)
                    }
                }
            }
        }.getOrNull()
    }

    suspend fun Context.generateThumbBitmapForMedia(mediaUri: Uri): Bitmap? = runCatching {
        withContext(Dispatchers.IO) {
            when (resolveType(contentResolver.getType(mediaUri))) {
                Type.IMAGE -> {
                    generateImageThumb(this@generateThumbBitmapForMedia, mediaUri)
                }
                Type.VIDEO -> {
                    generateVideoThumb(this@generateThumbBitmapForMedia, mediaUri)
                }
            }
        }
    }.getOrNull()

    private suspend fun getDefaultThumbOrThrow(context: Context, mediaUri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val thumbBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.loadThumbnail(
                mediaUri,
                Size(640, 480),
                null
            )
        } else {
            MediaStore.Video.Thumbnails.getThumbnail(
                contentResolver,
                DocumentsContractCompat.getDocumentId(mediaUri)?.toLongOrNull() ?: DocumentsContractCompat.getDocumentId(mediaUri)!!.split(":")[1].toLong(),
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        }
        ensureActive()
        thumbBitmap ?: throw NullPointerException("No default thumb found!")
    }

    private suspend fun generateImageThumb(context: Context, imageUri: Uri) = coroutineScope {
        runCatching {

            fun ContentResolver.getImageDimension(uri: Uri): Pair<Int, Int> {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                return openInputStream(uri)!!.use {
                    BitmapFactory.decodeStream(it, null, options).let {
                        options.outWidth to options.outHeight
                    }
                }
            }
            fun ContentResolver.getImageRotationInDegrees(uri: Uri): Int {
                return openInputStream(uri)!!.use {
                    val exif = ExifInterface(it)
                    val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        else -> 0
                    }
                    return@use rotation
                }
            }

            val contentResolver = context.contentResolver
            val (sourceWidth, sourceHeight) = contentResolver.getImageDimension(imageUri)
            ensureActive()
            val (scaleWidth, scaleHeight) = getScaledDimension(sourceWidth, sourceHeight)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                val heightRatio = (sourceHeight.toFloat() / scaleHeight.toFloat()).roundToInt()
                val widthRatio = (sourceWidth.toFloat() / scaleWidth.toFloat()).roundToInt()
                inSampleSize = Math.min(heightRatio, widthRatio)
            }

            val imageIS = contentResolver.openInputStream(imageUri)!!
            val croppedBitmap =
                BitmapFactory.decodeStream(imageIS, null, options)!!.let {
                    val rotation = contentResolver.getImageRotationInDegrees(imageUri)
                    if (rotation != 0) {
                        Bitmap.createBitmap(
                            it, 0, 0, it.width, it.height,
                            Matrix().apply {
                                postRotate(contentResolver.getImageRotationInDegrees(imageUri).toFloat())
                            },
                            true
                        )
                    } else {
                        it
                    }
                }
            imageIS.close()
            ensureActive()
            croppedBitmap
        }.getOrNull()
    }

    private suspend fun generateVideoThumb(context: Context, videoUri: Uri) = coroutineScope {
        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            val bitmap = retriever.getFrameAtTime(1000)!!
            retriever.release()
            val (sourceWidth, sourceHeight) = bitmap.width to bitmap.height
            val (scaleWidth, scaleHeight) = getScaledDimension(sourceWidth, sourceHeight)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                val heightRatio = (sourceHeight.toFloat() / scaleHeight.toFloat()).roundToInt()
                val widthRatio = (sourceWidth.toFloat() / scaleWidth.toFloat()).roundToInt()
                inSampleSize = Math.min(heightRatio, widthRatio)
            }
            val imageIS = ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                ByteArrayInputStream(it.toByteArray())
            }
            val croppedBitmap = BitmapFactory.decodeStream(imageIS, null, options)
            imageIS.close()
            ensureActive()
            croppedBitmap
        }.getOrNull()
    }

    private fun getScaledDimension(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val (newWidth, newHeight) = 600 to 600
        val xScale = newWidth.toFloat() / sourceWidth.toFloat()
        val yScale = newHeight.toFloat() / sourceHeight.toFloat()
        val scaleFactor = Math.min(xScale, yScale)
        return round(sourceWidth * scaleFactor).toInt() to round(sourceHeight * scaleFactor).toInt()
    }

    private fun resolveType(type: String?) = when {
        type?.startsWith("image") == true -> Type.IMAGE
        else -> Type.VIDEO
    }

    private enum class Type {
        VIDEO, IMAGE
    }
}