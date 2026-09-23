package com.quietstack.voicetrade.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.quietstack.voicetrade.core.common.ApplicationScope
import com.quietstack.voicetrade.domain.usecase.RegisterPushTokenUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** A price alert firing while the app isn't in an open voice session: this is how it reaches the phone
 * instead of waiting for the 15-minute poll ([Scheduler.start]'s [AlertsWorker] stays as the fallback). */
@AndroidEntryPoint
class VoiceTradeMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registerToken: RegisterPushTokenUseCase
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onNewToken(token: String) {
        scope.launch {
            registerToken(token).onFailure { Timber.w(it, "push token registration failed") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        Notifications.showRemote(applicationContext, title, body)
    }
}
