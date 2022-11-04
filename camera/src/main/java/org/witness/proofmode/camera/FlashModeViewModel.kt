package org.witness.proofmode.camera

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.witness.proofmode.camera.data.MediaType

class FlashModeViewModel: ViewModel() {
    private val _flashMode:MutableLiveData<Int> = MutableLiveData(ImageCapture.FLASH_MODE_OFF)
    val flashMode:LiveData<Int> get() = _flashMode
    private val _imageUri:MutableLiveData<Uri> = MutableLiveData()
    val imageUri:LiveData<Uri> get() = _imageUri
    private val _keyEvent:MutableLiveData<Int> = MutableLiveData(0)
    val keyEvent:LiveData<Int> get() = _keyEvent

    private val _videoImageBitmap:MutableLiveData<Bitmap> = MutableLiveData()
    val videoImageBitmap:LiveData<Bitmap> get() = _videoImageBitmap

    private val _videoUri:MutableLiveData<Uri> = MutableLiveData()
    val videoUri:LiveData<Uri> get() = _videoUri
    private val _mediaType:MutableLiveData<MediaType> = MutableLiveData(MediaType.TypeNone)
    val mediaType:LiveData<MediaType> get() = _mediaType


    fun setVideoImage(bitmap: Bitmap) {
        _videoImageBitmap.value = bitmap
        setMediaType(MediaType.TypeVideo)
    }

    private fun setMediaType(type: MediaType) {
        _mediaType.value = type
    }

    fun setFlashMode(mode:Int) {
        _flashMode.value = mode
    }
    fun tintButton(button: ImageView, @ColorRes color: Int,context: Context){
        val color1 = ContextCompat.getColor(context,color)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color1))
    }

    fun setEventFromKeyCode(code:Int){
        if(code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP){
            _keyEvent.value = code
        }
    }

    fun setImage(uri: Uri) {
        _imageUri.value = uri
        setMediaType(MediaType.TypeImage)
    }

    fun setVideoUri(uri: Uri) {
        _videoUri.value = uri
    }

}
