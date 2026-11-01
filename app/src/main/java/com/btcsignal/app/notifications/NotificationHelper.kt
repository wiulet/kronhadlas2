package com.btcsignal.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.btcsignal.app.MainActivity
import com.btcsignal.app.R
import com.btcsignal.app.data.model.Direction
import com.btcsignal.app.data.model.Signal
import java.text.SimpleDateFormat
import java.util.*

/**
 * Creates the Android Notification Channel and posts real system notifications for new
 * signals (spec sections 21-24). Every field shown here is read directly from the same
 * canonical [Signal] object rendered on the Live Signal Panel — there is no separate
 * notification data model.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID_SIGNALS = "signals_channel"
        const val CHANNEL_ID_SERVICE = "service_channel"
        const val TEST_NOTIFICATION_ID = 999
        private const val SOUND_ON_CHANNEL_SUFFIX = "_sound"
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels(soundEnabled: Boolean, vibrationEnabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val signalChannelId = signalChannelId(soundEnabled, vibrationEnabled)
        if (manager.getNotificationChannel(signalChannelId) == null) {
            val channel = NotificationChannel(
                signalChannelId,
                context.getString(R.string.notification_channel_signals_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_signals_desc)
                enableVibration(vibrationEnabled)
                if (soundEnabled) {
                    setSound(
                        android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    setSound(null, null)
                }
            }
            manager.createNotificationChannel(channel)
        }

        if (manager.getNotificationChannel(CHANNEL_ID_SERVICE) == null) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Background Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps live signal monitoring running" }
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun signalChannelId(soundEnabled: Boolean, vibrationEnabled: Boolean): String =
        "$CHANNEL_ID_SIGNALS-${if (soundEnabled) "s1" else "s0"}-${if (vibrationEnabled) "v1" else "v0"}"

    /** Posts a real system notification for [signal] (spec section 21). Idempotency is the caller's job (section 23). */
    fun notifySignal(signal: Signal, soundEnabled: Boolean, vibrationEnabled: Boolean) {
        ensureChannels(soundEnabled, vibrationEnabled)
        val directionLabel = if (signal.direction == Direction.GREEN) "\uD83D\uDFE2 GREEN" else "\uD83D\uDD34 RED"
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(signal.signalTimestampMillis))

        val deepLinkIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("btcsignal://signal/${signal.signalId}"),
            context,
            MainActivity::class.java
        )
        val pendingIntent = PendingIntent.getActivity(
            context, signal.signalId.hashCode(), deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, signalChannelId(soundEnabled, vibrationEnabled))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BTCUSDT 5M — $directionLabel")
            .setContentText(
                "Strategy ${signal.activeStrategyId} \u2022 Confidence ${"%.1f".format(signal.confidencePct)}% " +
                    "\u2022 Price ${"%.2f".format(signal.signalPrice)}"
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Prediction: $directionLabel\n" +
                        "Strategy: ${signal.activeStrategyName}\n" +
                        "Market: ${signal.marketRegime.trend.code} / ${signal.marketRegime.volatility.code} / ${signal.marketRegime.momentum.code}\n" +
                        "Signal price: ${signal.signalPrice}  \u2022  Time: $time UTC"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(signal.signalId.hashCode(), notification)
    }

    fun sendTestNotification(soundEnabled: Boolean, vibrationEnabled: Boolean) {
        ensureChannels(soundEnabled, vibrationEnabled)
        val notification = NotificationCompat.Builder(context, signalChannelId(soundEnabled, vibrationEnabled))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Test notification")
            .setContentText("This is what a BTCUSDT signal notification looks like.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(TEST_NOTIFICATION_ID, notification)
    }
}
