package org.witness.proofmode.camera.fragments

import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.Locale

class CameraViewModel : ViewModel() {
    var lensFacing: MutableLiveData<Int> = MutableLiveData(CameraSelector.LENS_FACING_BACK)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timerRunnable: Runnable
    private var startTime: Long = 0

    // LiveData to expose the elapsed time
    private val _elapsedTime = MutableLiveData<String>()
    val elapsedTime: LiveData<String> get() = _elapsedTime



    fun toggleLensFacing() {
        lensFacing.value = if (lensFacing.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    // Format the elapsed time
    private fun formatElapsedTime(elapsedTime: Long): String {
        val hours = (elapsedTime / 3600000).toInt()
        val minutes = (elapsedTime % 3600000 / 60000).toInt()
        val seconds = (elapsedTime % 60000 / 1000).toInt()

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(),"%02d:%02d", minutes, seconds)
        }
    }

    fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
        _elapsedTime.value = "00:00:00" // Reset the timer
    }
    fun startTimer() {
        startTime = System.currentTimeMillis()
        timerRunnable = object : Runnable {
            override fun run() {
                val timeDelta = System.currentTimeMillis() - startTime
                _elapsedTime.postValue(formatElapsedTime(timeDelta))
                handler.postDelayed(this, 1000)

            }
        }
        handler.post(timerRunnable)
    }

    override fun onCleared() {
        handler.removeCallbacks(timerRunnable)
        super.onCleared()
    }
}