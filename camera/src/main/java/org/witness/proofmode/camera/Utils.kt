package org.witness.proofmode.camera

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.camera.video.VideoRecordEvent.Finalize
import androidx.core.content.ContextCompat
import androidx.databinding.BindingAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

fun requestAllPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
    ActivityCompat.requestPermissions(
        activity,
        permissions,
        requestCode
    )
}

fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean = permissions.all {
    ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}


const val PREFS_FILE_NAME = "prefs"
const val FLASH_KEY = "flash"

/**
 * Method takes [nanoseconds] and formats it to the string form hh:mm:ss
 */
fun formatNanoseconds(nanoseconds: Long): String {
    val formatter = DecimalFormat("00")
    val actualSeconds = TimeUnit.SECONDS.convert(nanoseconds, TimeUnit.NANOSECONDS)
    val secs = actualSeconds.mod(60)
    val mins = actualSeconds.div(60).mod(60)
    val hrs = actualSeconds.div(60).div(60)
    val hours =
        if (hrs == 0L) "" else formatter.format(hrs) //String.format("%0sd",hrs) //if (hrs < 10 ) "0$hrs" else "$hrs"
    val seconds =
        formatter.format(secs) //String.format("%02d",secs) //if(secs < 10) "0$secs" else "$secs"
    val minutes = formatter.format(mins) //String.format("%02d",mins)
    return if (hrs > 0L) "$hours:$minutes:$seconds" else "$minutes:$seconds"
}

/**
 * Creates a bitmap thumbnail from video uri of the scheme type content://
 */
fun createVideoThumb(context: Context, uri: Uri): Bitmap? {
    try {
        val mediaMetadataRetriever = MediaMetadataRetriever()
        mediaMetadataRetriever.setDataSource(context, uri)
        return mediaMetadataRetriever.frameAtTime
    } catch (ex: Exception) {
        Toast
            .makeText(context, "Error retrieving bitmap", Toast.LENGTH_SHORT)
            .show()
    }
    return null

}

@BindingAdapter("app:imageUri")
fun bindImageUri(imageView: ImageView,uri:LiveData<Uri?>){
    uri.value?.let {
        imageView.setImageURI(it)
    }
}

@BindingAdapter("app:imageBitmap")
fun bindBitmap(imageView: ImageView,bitmap:LiveData<Bitmap?>){
    bitmap.value?.let {
        imageView.setImageBitmap(it)
    }
}

@BindingAdapter("app:videoUri")
fun bindVideoUri(videoView: VideoView,uri:LiveData<Uri?>){
    uri.value?.let {
        val mediaController = MediaController(videoView.context)
        mediaController.setAnchorView(videoView)
        videoView.setVideoURI(it)
        videoView.start()
    }
}


/**
 * The errorToString method in the VideoRecordEvent file/package
 * is package private, so we create a similar method here
 */
fun errorString(@Finalize.VideoRecordError error: Int):String {
    when (error) {
        Finalize.ERROR_NONE -> return "ERROR_NONE"
        Finalize.ERROR_UNKNOWN -> return "ERROR_UNKNOWN"
        Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> return "ERROR_FILE_SIZE_LIMIT_REACHED"
        Finalize.ERROR_INSUFFICIENT_STORAGE -> return "ERROR_INSUFFICIENT_STORAGE"
        Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> return "ERROR_INVALID_OUTPUT_OPTIONS"
        Finalize.ERROR_ENCODING_FAILED -> return "ERROR_ENCODING_FAILED"
        Finalize.ERROR_RECORDER_ERROR -> return "ERROR_RECORDER_ERROR"
        Finalize.ERROR_NO_VALID_DATA -> return "ERROR_NO_VALID_DATA"
        Finalize.ERROR_SOURCE_INACTIVE -> return "ERROR_SOURCE_INACTIVE"
    }

    // Should never reach here, but just in case...

    // Should never reach here, but just in case...
    return "Unknown($error)"
}

fun Context.getImagesFromUri(): List<Bitmap>{
    val images = mutableListOf<Bitmap>()
    val imagesCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.DISPLAY_NAME,
    )
    val cursor = this.contentResolver.query(imagesCollection, projection, null, null, null)


    return images
}


