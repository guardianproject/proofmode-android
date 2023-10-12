package org.witness.proofmode.camera.fragments

import androidx.camera.core.CameraSelector
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class CameraLensViewModel : ViewModel() {
    var lensFacing: MutableLiveData<Int> = MutableLiveData(CameraSelector.LENS_FACING_BACK)
        private set

    fun toggleLensFacing() {
        lensFacing.value = if (lensFacing.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }
}