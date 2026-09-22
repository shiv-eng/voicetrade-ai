package com.quietstack.voicetrade.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quietstack.voicetrade.MainActivity
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.common.ApplicationScope
import com.quietstack.voicetrade.domain.repository.SessionServiceController
import com.quietstack.voicetrade.domain.usecase.EndSessionUseCase
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground service (type microphone) that keeps the process, and so the Agora engine, alive while
 * the screen is off. The engine itself lives in a singleton; this service only owns the notification.
 */
@AndroidEntryPoint
class VoiceSessionService : Service() {

    @Inject lateinit var endSession: EndSessionUseCase
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            appScope.launch { endSession() }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Timber.w(e, "Could not enter foreground; continuing without a notification")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getService(
            this, 1, Intent(this, VoiceSessionService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_end), end)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    companion object {
        const val ACTION_END = "com.quietstack.voicetrade.action.END_SESSION"
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 1001
    }
}

@Singleton
class AndroidSessionServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionServiceController {

    private val main = Handler(Looper.getMainLooper())
    private var startedAt = 0L

    override fun start() {
        startedAt = SystemClock.elapsedRealtime()
        main.removeCallbacksAndMessages(null)
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, VoiceSessionService::class.java))
        }.onFailure { Timber.w(it, "startForegroundService failed") }
    }

    override fun stop() {
        // Stopping before the service has called startForeground() makes Android kill the app
        // (ForegroundServiceDidNotStartInTimeException), so give it a moment when a session fails fast.
        val wait = (startedAt + MIN_LIFETIME_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        main.postDelayed({ context.stopService(Intent(context, VoiceSessionService::class.java)) }, wait)
    }

    private companion object {
        const val MIN_LIFETIME_MS = 2_000L
    }
}
