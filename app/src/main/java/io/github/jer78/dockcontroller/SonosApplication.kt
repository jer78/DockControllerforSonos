package io.github.jer78.dockcontroller

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SonosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        MdiIcons.preload(this)
        // Settings > Setup > Debug Logging, followed as it's switched.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            SettingsManager(this@SonosApplication).debugLogging.collect { DebugLog.enabled = it }
        }
    }
}
