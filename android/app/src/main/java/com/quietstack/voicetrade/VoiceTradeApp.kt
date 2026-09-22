package com.quietstack.voicetrade

import android.app.Application
import com.quietstack.voicetrade.core.common.ApplicationScope
import com.quietstack.voicetrade.domain.repository.VoiceSessionRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VoiceTradeApp : Application() {

    @Inject lateinit var voiceSession: VoiceSessionRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        com.quietstack.voicetrade.notifications.Scheduler.start(this)
        // If the process was killed mid-session, stop the orphaned agent so it does not burn minutes.
        appScope.launch { voiceSession.cleanupOrphan() }
    }
}
