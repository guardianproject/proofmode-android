package org.witness.proofmode.camera

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.core.app.ActivityCompat
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
