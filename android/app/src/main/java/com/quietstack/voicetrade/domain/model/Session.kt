package com.quietstack.voicetrade.domain.model

import java.time.Instant

data class SessionInfo(
    val sessionId: String,
    val appId: String,
    val channel: String,
    val token: String,
    val uid: Int,
    val wsUrl: String,
    val paper: Boolean,
)

data class SessionSummary(
    val id: Long,
    val startedAt: Instant,
    val endedAt: Instant?,
    val summary: String,
)

/** Server to app events, already decoded and de-duplicated. */
sealed interface SessionEvent {
    data class Transcript(val role: Role, val text: String, val isFinal: Boolean, val messageId: String) : SessionEvent
    data class AgentStateChanged(val state: AgentState) : SessionEvent
    /** Mira was paused, or cut off and can say her answer again. */
    data class PlaybackChanged(val paused: Boolean, val resumable: Boolean) : SessionEvent
    data class CardShown(val messageId: String, val card: ActionCard) : SessionEvent
    data class PreviewCreated(val preview: OrderPreview) : SessionEvent
    data class PreviewClosed(val previewId: String, val state: PreviewState) : SessionEvent
    data class OrderUpdated(val order: Order) : SessionEvent
    data class BrokerStatusChanged(val status: BrokerStatus) : SessionEvent
    data class WatchlistChanged(val add: Boolean, val instrument: Instrument) : SessionEvent
    data class Failure(val code: String, val message: String, val retryable: Boolean) : SessionEvent
    data class System(val text: String) : SessionEvent
    data object Disconnected : SessionEvent
}
