package com.example.jaecoovoice

import android.app.Application
import com.example.jaecoovoice.engine.VoiceLog

/** Starts [VoiceLog] before any service runs, so a crash anywhere below is already captured. */
class JaecooVoiceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val info = packageManager.getPackageInfo(packageName, 0)
        VoiceLog.init(this, info.versionName.orEmpty(), info.longVersionCode)
    }
}
