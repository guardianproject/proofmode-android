package org.witness.proofmode.camera.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import org.witness.proofmode.camera.getImagesFromUri

class CapturedImagesDataSource(val contentResolver: ContentResolver,val uri:Uri,val projection:Array<String>) {

    fun fetchImages():List<DisplayImage>{
        val images = mutableListOf<DisplayImage>()
        /*val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
        )*/

        val cursor = this.contentResolver.query(uri, projection, null, null, null)


return emptyList()
    }
}