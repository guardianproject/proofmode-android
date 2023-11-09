package org.witness.proofmode

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size


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