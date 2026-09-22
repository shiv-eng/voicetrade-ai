package com.quietstack.voicetrade.data.remote.ws

import com.quietstack.voicetrade.data.local.prefs.SecureTokenStore
import com.quietstack.voicetrade.data.mapper.toDomain
import com.quietstack.voicetrade.data.remote.AppJson
import com.quietstack.voicetrade.data.remote.dto.AgentStateData
import com.quietstack.voicetrade.data.remote.dto.PlaybackData
import com.quietstack.voicetrade.data.remote.dto.BrokerStatusDto
import com.quietstack.voicetrade.data.remote.dto.CardData
import com.quietstack.voicetrade.data.remote.dto.ErrorData
import com.quietstack.voicetrade.data.remote.dto.OrderUpdateData
import com.quietstack.voicetrade.data.remote.dto.PreviewClosedData
import com.quietstack.voicetrade.data.remote.dto.PreviewCreatedData
import com.quietstack.voicetrade.data.remote.dto.TranscriptData
import com.quietstack.voicetrade.data.remote.dto.WatchlistData
import com.quietstack.voicetrade.data.remote.dto.WsEnvelope
import com.quietstack.voicetrade.domain.model.AgentState
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.Role
import com.quietstack.voicetrade.domain.model.SessionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Server to app event stream for one session. Collect to connect; cancel to close. */
interface SessionEventsSocket {
    fun connect(sessionId: String, wsUrl: String): Flow<SessionEvent>
}

/**
 * OkHttp WebSocket with automatic reconnect. Every envelope carries a `seq`; after a reconnect the
 * last seen value is sent as `lastSeq` so the server can replay the gap, and anything at or below it
 * is dropped here so cards are never duplicated (PRD 12.2, TC-12).
 */
@Singleton
class OkHttpSessionEventsSocket @Inject constructor(
    private val client: OkHttpClient,
    private val store: SecureTokenStore,
) : SessionEventsSocket {

    override fun connect(sessionId: String, wsUrl: String): Flow<SessionEvent> = channelFlow {
        var lastSeq = 0
        var attempt = 0
        while (true) {
            val opened = openOnce(wsUrl, lastSeq) { seq -> lastSeq = maxOf(lastSeq, seq) }
            var gotEvent = false
            try {
                opened.collect { event ->
                    gotEvent = true
                    attempt = 0
                    send(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Events socket dropped")
            }
            send(SessionEvent.Disconnected)
            attempt = if (gotEvent) 1 else attempt + 1
            if (attempt > MAX_RECONNECTS) break
            delay(minOf(1_000L shl attempt.coerceAtMost(4), 15_000L))
        }
    }.flowOn(Dispatchers.IO)

    private fun openOnce(wsUrl: String, lastSeq: Int, onSeq: (Int) -> Unit): Flow<SessionEvent> = callbackFlow {
        val url = if (lastSeq > 0) wsUrl + (if ('?' in wsUrl) "&" else "?") + "lastSeq=$lastSeq" else wsUrl
        val request = Request.Builder().url(url)
            .apply { store.accessToken?.let { header("Authorization", "Bearer $it") } }
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = runCatching { AppJson.instance.decodeFromString<WsEnvelope>(text) }.getOrNull() ?: return
                if (envelope.seq in 1..lastSeq) return
                onSeq(envelope.seq)
                decode(envelope)?.let { trySendBlocking(it) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        })
        awaitClose { socket.close(1000, "bye") }
    }

    companion object {
        private const val MAX_RECONNECTS = 6

        /** Unknown or malformed events are skipped so a newer backend never crashes an older app. */
        fun decode(env: WsEnvelope): SessionEvent? = try {
            val json = AppJson.instance
            when (env.type) {
                "transcript" -> json.decodeFromJsonElement<TranscriptData>(env.data).let {
                    SessionEvent.Transcript(parseRole(it.role), it.text, it.isFinal, it.messageId)
                }
                "agent_state" -> SessionEvent.AgentStateChanged(parseAgentState(json.decodeFromJsonElement<AgentStateData>(env.data).state))
                "playback" -> json.decodeFromJsonElement<PlaybackData>(env.data).let { SessionEvent.PlaybackChanged(it.paused, it.resumable) }
                "card" -> json.decodeFromJsonElement<CardData>(env.data).let { SessionEvent.CardShown(it.messageId, it.card.toDomain()) }
                "preview_created" -> SessionEvent.PreviewCreated(json.decodeFromJsonElement<PreviewCreatedData>(env.data).preview.toDomain())
                "preview_closed" -> json.decodeFromJsonElement<PreviewClosedData>(env.data).let {
                    SessionEvent.PreviewClosed(it.previewId, parsePreviewState(it.reason))
                }
                "order_update" -> SessionEvent.OrderUpdated(json.decodeFromJsonElement<OrderUpdateData>(env.data).order.toDomain())
                "broker_status" -> SessionEvent.BrokerStatusChanged(json.decodeFromJsonElement<BrokerStatusDto>(env.data).toDomain())
                "watchlist" -> json.decodeFromJsonElement<WatchlistData>(env.data).let {
                    SessionEvent.WatchlistChanged(it.action.equals("add", true), it.instrument.toDomain())
                }
                "error" -> json.decodeFromJsonElement<ErrorData>(env.data).let { SessionEvent.Failure(it.code, it.message, it.retryable) }
                else -> null
            }
        } catch (e: Exception) {
            Timber.w(e, "Skipping malformed '%s' event", env.type)
            null
        }

        fun parseRole(raw: String) = when (raw.lowercase()) {
            "user" -> Role.USER
            "agent", "assistant" -> Role.AGENT
            else -> Role.SYSTEM
        }

        fun parseAgentState(raw: String) = when (raw.lowercase()) {
            "listening" -> AgentState.LISTENING
            "thinking" -> AgentState.THINKING
            "speaking" -> AgentState.SPEAKING
            "awaiting_confirmation" -> AgentState.AWAITING_CONFIRMATION
            else -> AgentState.IDLE
        }

        fun parsePreviewState(raw: String) = when (raw.lowercase()) {
            "confirmed" -> PreviewState.CONFIRMED
            "rejected" -> PreviewState.REJECTED
            "replaced" -> PreviewState.REPLACED
            else -> PreviewState.EXPIRED
        }
    }
}

