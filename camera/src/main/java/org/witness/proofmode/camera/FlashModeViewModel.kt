package org.witness.proofmode.camera

import android.content.Context
import android.content.res.ColorStateList
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

    fun setFlashMode(mode:Int) {
        _flashMode.value = mode
    }
    fun tintButton(button: ImageView, @ColorRes color: Int,context: Context){
        val color1 = ContextCompat.getColor(context,color)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color1))
    }
}