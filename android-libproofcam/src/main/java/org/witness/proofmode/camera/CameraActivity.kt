package org.witness.proofmode.camera

import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import org.witness.proofmode.ProofMode.PREF_OPTION_BLOCK_AI
import org.witness.proofmode.camera.fragments.CameraScreen


class CameraActivity : ComponentActivity(), SensorEventListener {


 //   private val PREF_OPTION_AI = "blockAI"
    private val PREF_OPTION_CREDENTIALS = "addCR"

    companion object {
        var useCredentials = true
        var useBlockAIFlag = true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the Android version is lower than Jellybean, use this call to hide
        // the status bar.

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

        setContent {

            CameraScreen(this, modifier = Modifier.fillMaxSize(), onClose = {
                finish()
            })

        }

        //setContentView(R.layout.camera_main)
        useCredentials = intent.getBooleanExtra(PREF_OPTION_CREDENTIALS, true)
        useBlockAIFlag = intent.getBooleanExtra(PREF_OPTION_BLOCK_AI, true)

    }

    override fun onResume() {
        super.onResume()
        val sensorManager = this.getSystemService(SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onPause() {
        super.onPause()
        val sensorManager = this.getSystemService(SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.unregisterListener(this, accelerometer)
    }

    fun getScreenOrientation(): Int {
        return orientation
    }

    var orientation = 0

    override fun onSensorChanged(event: SensorEvent?) {

        if (event != null) {
            if (event.values[1] < 6.5 && event.values[1] > -6.5) {
                if (orientation != 1) {
                //    Log.d("Sensor", "Landscape");
                }
                orientation = Surface.ROTATION_90
            } else {
                if (orientation != 0) {
                //    Log.d("Sensor", "Portrait");
                }
                orientation = Surface.ROTATION_0
            }
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        //nothing to do
    }
}
