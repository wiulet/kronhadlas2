package com.btcsignal.app.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("live_service_prefs", Context.MODE_PRIVATE)
        val wasRunning = prefs.getBoolean("was_running", false)
        if (wasRunning) {
            val serviceIntent = Intent(context, LiveMonitoringService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
