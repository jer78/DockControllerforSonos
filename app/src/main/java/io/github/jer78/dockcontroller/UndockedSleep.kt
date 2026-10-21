package io.github.jer78.dockcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Whether the app can sleep: the screen's off and the tablet's off its dock
// (not charging - the dock is what charges it). Nobody's looking and nothing
// is being shown, so there's no need to keep talking to the speaker or Home
// Assistant (see SonosDashboardManager.setAsleep). On the dock it keeps
// running with the screen off, for things like Continue with Random Album.
class UndockedSleep(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)

    private var screenOn = power?.isInteractive ?: true
    private var charging = isCharging(app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

    private val _canSleep = MutableStateFlow(!screenOn && !charging)
    val canSleep: StateFlow<Boolean> = _canSleep.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
            }
            _canSleep.value = !screenOn && !charging
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun isCharging(battery: Intent?): Boolean =
        (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}

// Whether the screen this is on can be seen - false once the app's in the
// background or the screen's off - so the mic and sensors it uses can stop.
@Composable
fun isScreenVisible(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
