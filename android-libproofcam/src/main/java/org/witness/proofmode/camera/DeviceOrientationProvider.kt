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
import kotlin.math.abs

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
                // Start the filter afresh rather than easing in from a stale reading.
                hasSample = false
                sensorManager.registerListener(this,accelerometer, SensorManager.SENSOR_DELAY_UI)
            }

            Lifecycle.Event.ON_PAUSE -> {
                sensorManager.unregisterListener(this)
            }

            Lifecycle.Event.ON_DESTROY -> {
                sensorManager.unregisterListener(this)
            } else -> {}
        }
    }

    private companion object {
        /** Low-pass weight for each new sample; smooths out hand shake. */
        const val FILTER_ALPHA = 0.25f

        /** Below this much in-plane gravity the phone is lying flat: keep the last answer. */
        const val MIN_IN_PLANE_GRAVITY = 3f

        /**
         * One axis must dominate the other by this factor before the orientation is
         * considered changed. That puts the switch points about 35° and 55° from
         * upright, leaving a band around the diagonal where nothing happens.
         */
        const val DOMINANCE = 1.4f

        /** How long a new orientation has to hold before it is reported. */
        const val SETTLE_NS = 350_000_000L
    }

    private var filteredX = 0f
    private var filteredY = 0f
    private var hasSample = false
    private var candidate = Surface.ROTATION_0
    private var candidateSinceNs = 0L

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (!hasSample) {
            filteredX = event.values[0]
            filteredY = event.values[1]
            hasSample = true
        } else {
            filteredX += FILTER_ALPHA * (event.values[0] - filteredX)
            filteredY += FILTER_ALPHA * (event.values[1] - filteredY)
        }
        val x = filteredX
        val y = filteredY
        val current = _rotation.value
        val target = when {
            abs(x) < MIN_IN_PLANE_GRAVITY && abs(y) < MIN_IN_PLANE_GRAVITY -> current
            // Gravity along +x means the device's left edge is down (turned
            // counter-clockwise), which is ROTATION_90; -x is the other landscape.
            abs(x) > DOMINANCE * abs(y) -> if (x > 0) Surface.ROTATION_90 else Surface.ROTATION_270
            abs(y) > DOMINANCE * abs(x) -> Surface.ROTATION_0
            else -> current
        }
        // Only report a change once it has held for a moment, so a passing tilt or a
        // jolt never flips the UI (or the capture rotation) and straight back.
        if (target != candidate) {
            candidate = target
            candidateSinceNs = event.timestamp
        }
        if (candidate != current && event.timestamp - candidateSinceNs >= SETTLE_NS) {
            _rotation.value = candidate
        }
    }
}
