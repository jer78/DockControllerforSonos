package com.example.sonosdockcontroller

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class SonosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
