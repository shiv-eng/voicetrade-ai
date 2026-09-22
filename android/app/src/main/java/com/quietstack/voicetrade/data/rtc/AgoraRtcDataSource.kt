package com.quietstack.voicetrade.data.rtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.quietstack.voicetrade.core.common.AppError
import dagger.hilt.android.qualifiers.ApplicationContext
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Agora [RtcEngine] (a process-wide singleton, so audio survives configuration changes and
 * screen-off while the foreground service keeps the process alive).
 */
@Singleton
class AgoraRtcDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : RtcDataSource {

    private val _events = MutableSharedFlow<RtcEvent>(extraBufferCapacity = 64)
    override val events: Flow<RtcEvent> = _events.asSharedFlow()

    private var engine: RtcEngine? = null
    private var currentAppId: String? = null
    private var joinResult: CompletableDeferred<Result<Unit>>? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val handler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            joinResult?.complete(Result.success(Unit))
            _events.tryEmit(RtcEvent.Joined(channel))
        }

        override fun onError(err: Int) {
            Timber.w("Agora error %d", err)
            joinResult?.complete(Result.failure(AppError.RtcJoinFailed(err)))
            _events.tryEmit(RtcEvent.Error(err))
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            _events.tryEmit(RtcEvent.AgentJoined(uid))
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            _events.tryEmit(RtcEvent.AgentLeft(uid))
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            when (state) {
                Constants.CONNECTION_STATE_RECONNECTING -> _events.tryEmit(RtcEvent.ConnectionLost(reconnecting = true))
                Constants.CONNECTION_STATE_FAILED -> {
                    joinResult?.complete(Result.failure(AppError.RtcJoinFailed(reason)))
                    _events.tryEmit(RtcEvent.ConnectionLost(reconnecting = false))
                }
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String) {
            _events.tryEmit(RtcEvent.TokenWillExpire(token))
        }

        override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
            if (speakers.isNullOrEmpty()) {
                _events.tryEmit(RtcEvent.Volume(0f, 0f))
                return
            }
            var local = 0
            var remote = 0
            for (s in speakers) if (s.uid == 0) local = s.volume else remote = maxOf(remote, s.volume)
            _events.tryEmit(RtcEvent.Volume(local / 255f, remote / 255f))
        }
    }

    override suspend fun join(
        appId: String,
        channel: String,
        token: String,
        uid: Int,
        publishMic: Boolean,
    ): Result<Unit> {
        val rtc = try {
            ensureEngine(appId)
        } catch (e: Exception) {
            Timber.e(e, "Agora engine init failed")
            return Result.failure(AppError.RtcJoinFailed())
        }
        val pending = CompletableDeferred<Result<Unit>>()
        joinResult = pending

        rtc.enableAudio()
        rtc.enableAudioVolumeIndication(200, 3, true)
        rtc.setAINSMode(true, 0)
        rtc.setEnableSpeakerphone(true)
        requestAudioFocus()

        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = publishMic
            autoSubscribeAudio = true
        }
        val code = rtc.joinChannel(token, channel, uid, options)
        if (code != 0) {
            joinResult = null
            return Result.failure(AppError.RtcJoinFailed(code))
        }
        val outcome = withTimeoutOrNull(JOIN_TIMEOUT_MS) { pending.await() }
        joinResult = null
        return outcome ?: Result.failure(AppError.RtcJoinFailed())
    }

    override fun setMicMuted(muted: Boolean) {
        engine?.muteLocalAudioStream(muted)
    }

    override fun renewToken(token: String) {
        engine?.renewToken(token)
    }

    override fun leave() {
        engine?.leaveChannel()
        abandonAudioFocus()
        RtcEngine.destroy()
        engine = null
        currentAppId = null
    }

    private fun ensureEngine(appId: String): RtcEngine {
        engine?.let { existing -> if (currentAppId == appId) return existing }
        if (engine != null) RtcEngine.destroy()
        // Read the app context outside apply{}: RtcEngineConfig has its own (null) `context` property that would shadow ours.
        val appContext = this.context.applicationContext
        val config = RtcEngineConfig().apply {
            mContext = appContext
            mAppId = appId
            mEventHandler = handler
            mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            // Tuned by Agora for talking to a Conversational AI agent (echo control, speech-first).
            mAudioScenario = Constants.AUDIO_SCENARIO_AI_CLIENT
        }
        return RtcEngine.create(config).also {
            it.setAudioProfile(Constants.AUDIO_PROFILE_DEFAULT)
            engine = it
            currentAppId = appId
        }
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    -> _events.tryEmit(RtcEvent.AudioFocusLost)
                    AudioManager.AUDIOFOCUS_GAIN -> _events.tryEmit(RtcEvent.AudioFocusRegained)
                }
            }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
