package org.witness.proofmode.camera

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class FlashModeViewModel: ViewModel() {
    private val _flashMode:MutableLiveData<Int> = MutableLiveData(ImageCapture.FLASH_MODE_OFF)
    val flashMode:LiveData<Int> get() = _flashMode
    private val _imageUri:MutableLiveData<Uri> = MutableLiveData()
    val imageUri:LiveData<Uri> get() = _imageUri

    fun setFlashMode(mode:Int) {
        _flashMode.value = mode
    }
    fun tintButton(button: ImageView, @ColorRes color: Int,context: Context){
        val color1 = ContextCompat.getColor(context,color)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color1))
    }

    fun setImage(uri: Uri) {
        _imageUri.value = uri
    }
}