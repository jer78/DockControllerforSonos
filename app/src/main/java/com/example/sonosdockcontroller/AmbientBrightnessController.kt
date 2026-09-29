package com.example.sonosdockcontroller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dims the screen in a dark room (e.g. overnight) using the device's ambient
 * light sensor, instead of always running the screensaver at full system
 * brightness. Not every device has a light sensor - [brightness] just stays
 * at [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE] (-1f, "leave it to
 * the system") if one isn't available.
 */
class AmbientBrightnessController(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val _brightness = MutableStateFlow(-1f)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    // Exponential smoothing so a brief hand-wave over the sensor (or a TV
    // flicker) doesn't yank brightness around - only a sustained light-level
    // change moves it.
    private var smoothedLux = -1f

    fun start() {
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values[0]
        smoothedLux = if (smoothedLux < 0f) lux else smoothedLux * 0.9f + lux * 0.1f
        // Narrow band, low cutoffs: normal indoor room light is already
        // ~100-300 lux and daylight far more, so anything above DIM_LUX_CUTOFF
        // is "bright enough" and left at system/user brightness. Only true
        // near-darkness (lights off at night) dims at all. If a specific dock
        // placement/case occludes the sensor and this still triggers in a lit
        // room, lower these further or raise them if it never triggers at night.
        _brightness.value = when {
            smoothedLux < DARK_LUX_CUTOFF -> DARK_BRIGHTNESS // near-pitch-black (lights off at night)
            smoothedLux < DIM_LUX_CUTOFF -> DIM_BRIGHTNESS   // just a nightlight/lamp glow
            else -> -1f                                      // bright enough - let the system/user brightness setting control it
        }
    }

    companion object {
        private const val DARK_LUX_CUTOFF = 3f
        private const val DIM_LUX_CUTOFF = 8f
        private const val DARK_BRIGHTNESS = 0.15f
        private const val DIM_BRIGHTNESS = 0.6f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
