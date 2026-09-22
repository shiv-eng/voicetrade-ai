package com.quietstack.voicetrade.data.rtc

import kotlinx.coroutines.flow.Flow

sealed interface RtcEvent {
    data class Joined(val channel: String) : RtcEvent
    data class AgentJoined(val uid: Int) : RtcEvent
    data class AgentLeft(val uid: Int) : RtcEvent

    /** Levels are 0f..1f: [local] is the user's mic, [remote] is the agent's voice. */
    data class Volume(val local: Float, val remote: Float) : RtcEvent
    data class ConnectionLost(val reconnecting: Boolean) : RtcEvent
    data class TokenWillExpire(val token: String) : RtcEvent
    data class Error(val code: Int) : RtcEvent
    data object AudioFocusLost : RtcEvent
    data object AudioFocusRegained : RtcEvent
}

/** Thin wrapper over the Agora RTC engine so the rest of the app never touches the SDK. */
interface RtcDataSource {
    val events: Flow<RtcEvent>

    /** Suspends until the channel is joined; fails after 10 s (PRD 14.3). */
    suspend fun join(appId: String, channel: String, token: String, uid: Int, publishMic: Boolean): Result<Unit>
    fun setMicMuted(muted: Boolean)
    fun renewToken(token: String)
    fun leave()
}
