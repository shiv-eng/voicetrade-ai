package com.quietstack.voicetrade.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.quietstack.voicetrade.MainActivity
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.domain.model.PriceAlert
import java.math.BigDecimal

object Notifications {
    const val CHANNEL_ALERTS = "price_alerts"
    const val CHANNEL_BRIEFING = "briefing"
    const val EXTRA_PROMPT = "launch_prompt"
    const val EXTRA_OPEN = "launch_open"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Price alerts", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_BRIEFING, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(context: Context, requestCode: Int, prompt: String? = null, open: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_PROMPT, prompt)
            .putExtra(EXTRA_OPEN, open)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // canNotify() already does the real check (POST_NOTIFICATIONS on 33+); lint can't trace through a
    // private helper to see that, so it flags the call below as unchecked without this.
    @SuppressLint("MissingPermission")
    fun showAlert(context: Context, alert: PriceAlert) {
        if (!canNotify(context)) return
        val price = alert.triggerPrice ?: alert.target
        val sym = if (alert.currency == "USD") "$" else "₹"
        val text = "${alert.name} is now $sym${price.stripTrailingZeros().toPlainString()}, " +
            "${if (alert.direction == "above") "above" else "below"} your $sym${alert.target.stripTrailingZeros().toPlainString()} alert"
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Price alert: ${alert.symbol}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, alert.id.toInt(), prompt = "How is ${alert.name} doing?"))
            .build()
        NotificationManagerCompat.from(context).notify(2000 + alert.id.toInt(), n)
    }

    /** A push that arrived while the app was in the foreground: FCM only auto-shows notifications when
     * backgrounded, so a visible one is our job the rest of the time. */
    @SuppressLint("MissingPermission")  // see the note on showAlert() above
    fun showRemote(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, 3000, prompt = title))
            .build()
        NotificationManagerCompat.from(context).notify(3000 + text.hashCode(), n)
    }

    @SuppressLint("MissingPermission")  // see the note on showAlert() above
    fun showBriefing(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_BRIEFING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, 1, prompt = "Give me my morning market briefing"))
            .build()
        NotificationManagerCompat.from(context).notify(1000, n)
    }
}
