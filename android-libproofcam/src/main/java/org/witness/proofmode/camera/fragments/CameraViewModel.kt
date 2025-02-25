package org.witness.proofmode.camera.fragments

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.core.net.toFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.witness.proofmode.camera.fragments.CameraConstants.NEW_MEDIA_EVENT
import java.io.FileNotFoundException
import java.util.Locale

class CameraViewModel(private val app: Application) : AndroidViewModel(app) {
    var lensFacing: MutableLiveData<Int> = MutableLiveData(CameraSelector.LENS_FACING_BACK)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timerRunnable: Runnable
    private var startTime: Long = 0

    // LiveData to expose the elapsed time
    private val _elapsedTime = MutableLiveData<String>()
    val elapsedTime: LiveData<String> get() = _elapsedTime


    fun sendLocalCameraEvent(newMediaFile: Uri, cameraEventType: CameraEventType) {
        if (cameraEventType == CameraEventType.NEW_VIDEO) {
            val intent = Intent(NEW_MEDIA_EVENT).apply { data = newMediaFile }
            LocalBroadcastManager.getInstance(app).sendBroadcast(intent)
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

                try {
                    var f = newMediaFile.toFile()
                    MediaStore.Images.Media.insertImage(
                        app.contentResolver,
                        f.absolutePath, f.name, null
                    )
                    app.sendBroadcast(
                        Intent(
                            Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)
                        )
                    )
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                }
            }
        }

    }

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

enum class CameraEventType {
    NEW_IMAGE,
    NEW_VIDEO
}

object CameraConstants {
    const val NEW_MEDIA_EVENT = "org.witness.proofmode.NEW_MEDIA"
}