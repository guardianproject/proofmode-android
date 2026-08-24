package org.witness.proofmode.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceOrientationProvider(context: Context): LifecycleEventObserver, SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val _rotation = MutableStateFlow(Surface.ROTATION_0)
    val rotation: StateFlow<Int> = _rotation.asStateFlow()

    // Automatically starts and stops sensing based on the UI lifecycle
    override fun onStateChanged(
        source: LifecycleOwner,
        event: Lifecycle.Event
    ) {

        when(event){
            Lifecycle.Event.ON_RESUME -> {
                sensorManager.registerListener(this,accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            }

            Lifecycle.Event.ON_PAUSE -> {
                sensorManager.unregisterListener(this)
            }

            Lifecycle.Event.ON_DESTROY -> {
                sensorManager.unregisterListener(this)
            } else -> {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val newRotation = if (it.values[1] < 6.5 && it.values[1] > -6.5) {
                Surface.ROTATION_90
            } else {
                Surface.ROTATION_0
            }
            _rotation.value = newRotation
        }
    }
}