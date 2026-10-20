package io.github.jer78.dockcontroller

import android.content.Context
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
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

    // Lights out: near-pitch-black, with a little hysteresis so the level
    // hovering around the cutoff doesn't flip it back and forth. Drives
    // DarkRoomBlackout.
    private val _isDark = MutableStateFlow(false)
    val isDark: StateFlow<Boolean> = _isDark.asStateFlow()

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
        _isDark.value = if (_isDark.value) smoothedLux < LIGHTS_ON_LUX else smoothedLux < DARK_LUX_CUTOFF
    }

    companion object {
        private const val DARK_LUX_CUTOFF = 3f
        private const val DIM_LUX_CUTOFF = 8f
        private const val LIGHTS_ON_LUX = 5f
        private const val DARK_BRIGHTNESS = 0.15f
        private const val DIM_BRIGHTNESS = 0.6f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// Over an idle screen (Cover Flow / TV Ambient) once the room has been dark
// for DARK_SETTLE_MS: fades to black at the lowest backlight, so the
// screensaver doesn't light up the room all night. It comes back by itself
// when the lights go on; a tap brings it back for PEEK_MS without waking
// anything underneath. Also applies the dark-room dimming to [window] - the
// one place the window's brightness is set. onFullyBlack hears when the
// screen is fully black and when it lights up again, so what's underneath
// can stop moving meanwhile (nothing can see it).
@Composable
fun DarkRoomBlackout(idle: Boolean, window: Window, onFullyBlack: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val controller = remember { AmbientBrightnessController(context) }
    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.stop() }
    }
    val brightness by controller.brightness.collectAsState()
    val roomIsDark by controller.isDark.collectAsState()

    var blackedOut by remember { mutableStateOf(false) }
    var peeks by remember { mutableIntStateOf(0) }
    LaunchedEffect(roomIsDark, idle, peeks) {
        blackedOut = false
        if (roomIsDark && idle) {
            delay(if (peeks > 0) PEEK_MS else DARK_SETTLE_MS)
            blackedOut = true
        }
    }

    val blackAlpha by animateFloatAsState(
        targetValue = if (blackedOut) 1f else 0f,
        animationSpec = tween(if (blackedOut) 2000 else 500),
        label = "darkRoomBlackout"
    )
    // Down to the lowest backlight only once fully black, and back up
    // before it fades in.
    val fullyBlack = blackedOut && blackAlpha == 1f
    val currentOnFullyBlack by rememberUpdatedState(onFullyBlack)
    LaunchedEffect(fullyBlack) { currentOnFullyBlack(fullyBlack) }
    LaunchedEffect(brightness, fullyBlack) {
        val attributes = window.attributes
        attributes.screenBrightness = if (fullyBlack) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF else brightness
        window.attributes = attributes
    }

    if (blackAlpha > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = blackAlpha }
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { peeks++ } }
        )
    }
}

// How long the room has to stay dark before the screen goes black - long
// enough to cross a room after switching the lights off.
private const val DARK_SETTLE_MS = 10_000L
// How long a tap on the black screen shows it again.
private const val PEEK_MS = 30_000L
