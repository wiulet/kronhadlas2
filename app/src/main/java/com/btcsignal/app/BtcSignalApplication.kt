package com.btcsignal.app

import android.app.Application
import com.btcsignal.app.notifications.NotificationHelper

class BtcSignalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.btcsignal.app.kronos.KronosModelManager.init(this)
        NotificationHelper(this).ensureChannels(soundEnabled = true, vibrationEnabled = true)
    }
}
