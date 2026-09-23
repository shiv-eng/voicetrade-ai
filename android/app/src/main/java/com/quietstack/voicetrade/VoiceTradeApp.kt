package com.quietstack.voicetrade

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import com.quietstack.voicetrade.core.common.ApplicationScope
import com.quietstack.voicetrade.domain.repository.VoiceSessionRepository
import com.quietstack.voicetrade.domain.usecase.RegisterPushTokenUseCase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

/** Forwards warnings, errors and uncaught crashes to Crashlytics: without this, a failure during a demo
 * (or on a user's phone) leaves no trace beyond whatever was visible on screen at the time. */
private class CrashlyticsTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.WARN) return  // breadcrumbs only; not every debug line
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log("${tag ?: "App"}: $message")
        if (t != null) crashlytics.recordException(t)
    }
}

@HiltAndroidApp
class VoiceTradeApp : Application() {

    @Inject lateinit var voiceSession: VoiceSessionRepository
    @Inject lateinit var registerPushToken: RegisterPushTokenUseCase
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        com.quietstack.voicetrade.notifications.Scheduler.start(this)
        // If the process was killed mid-session, stop the orphaned agent so it does not burn minutes.
        appScope.launch { voiceSession.cleanupOrphan() }
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            // On by default even for a debug build: right now debug *is* the demo build, and a crash with
            // no report is worse than a little dev-time noise in the Crashlytics console.
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            Timber.plant(CrashlyticsTree())
            // Re-register on every cold start, not just when the token changes: sign-in can happen after
            // the token already exists, and the backend only learns about a token once we tell it.
            appScope.launch {
                runCatching { FirebaseMessaging.getInstance().token.await() }
                    .onSuccess { registerPushToken(it) }
                    .onFailure { Timber.w(it, "FCM token fetch failed") }
            }
        }
    }
}
