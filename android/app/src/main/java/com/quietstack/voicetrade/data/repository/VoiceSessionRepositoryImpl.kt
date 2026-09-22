package com.quietstack.voicetrade.data.repository

import com.quietstack.voicetrade.core.common.ApplicationScope
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.data.local.prefs.SecureTokenStore
import com.quietstack.voicetrade.data.local.prefs.SettingsDataStore
import com.quietstack.voicetrade.data.mapper.toDomain
import com.quietstack.voicetrade.data.remote.apiCall
import com.quietstack.voicetrade.data.remote.dto.StartSessionRequest
import com.quietstack.voicetrade.data.remote.dto.TextRequest
import com.quietstack.voicetrade.data.rtc.RtcDataSource
import com.quietstack.voicetrade.data.rtc.RtcEvent
import com.quietstack.voicetrade.data.voice.DeviceVoice
import com.quietstack.voicetrade.data.voice.ListenEvent
import com.quietstack.voicetrade.domain.model.AppSettings
import com.quietstack.voicetrade.domain.model.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.takeWhile
import com.quietstack.voicetrade.domain.model.ActionCard
import com.quietstack.voicetrade.domain.model.AgentState
import com.quietstack.voicetrade.domain.model.BrokerStatus
import com.quietstack.voicetrade.domain.model.ConversationMessage
import com.quietstack.voicetrade.domain.model.Order
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.Role
import com.quietstack.voicetrade.domain.model.SessionEvent
import com.quietstack.voicetrade.domain.model.SessionInfo
import com.quietstack.voicetrade.domain.repository.SessionServiceController
import com.quietstack.voicetrade.domain.repository.VoiceSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates one live session: backend REST, the Agora channel and the events WebSocket.
 * The conversation list is rebuilt from server events; everything else is state flags for the UI.
 */
@Singleton
class VoiceSessionRepositoryImpl @Inject constructor(
    private val gateway: BackendGateway,
    private val store: SecureTokenStore,
    private val settingsStore: SettingsDataStore,
    private val service: SessionServiceController,
    private val deviceVoice: DeviceVoice,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : VoiceSessionRepository {

    /** The language the user picked first (onboarding or Settings) is the language of the whole conversation. */
    private fun talkLanguage(): String = if (com.quietstack.voicetrade.core.i18n.AppLocale.effective(appContext) == "hi") "hinglish" else "en"

    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    override val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    override val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    override val micLevel: StateFlow<Float> = _micLevel.asStateFlow()
    private val _remoteLevel = MutableStateFlow(0f)
    override val remoteLevel: StateFlow<Float> = _remoteLevel.asStateFlow()
    private val _agoraAudio = MutableStateFlow(false)
    override val agoraAudio: StateFlow<Boolean> = _agoraAudio.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    private val _canResume = MutableStateFlow(false)
    override val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    private val _isLive = MutableStateFlow(false)
    override val isLive: StateFlow<Boolean> = _isLive.asStateFlow()

    private val _brokerStatus = MutableStateFlow<BrokerStatus?>(null)
    override val brokerStatus: StateFlow<BrokerStatus?> = _brokerStatus.asStateFlow()

    private val _liveCaption = MutableStateFlow("")
    override val liveCaption: StateFlow<String> = _liveCaption.asStateFlow()

    private val _voiceConnected = MutableStateFlow(false)
    override val voiceConnected: StateFlow<Boolean> = _voiceConnected.asStateFlow()

    override var startedAtMillis: Long? = null
        private set

    private val lifecycleLock = Mutex()
    private var session: SessionInfo? = null
    private var rtc: RtcDataSource? = null
    private var eventsJob: Job? = null
    private var rtcJob: Job? = null
    private var micGranted = false
    private var counter = 0
    private var deviceMode = false
    private var voiceLoop: Job? = null
    private var pendingReply: CompletableDeferred<String>? = null

    override suspend fun start(micGranted: Boolean, resumeFrom: List<ConversationMessage>): Result<Unit> = lifecycleLock.withLock {
        if (_isLive.value) return Result.success(Unit)
        this.micGranted = micGranted
        _messages.value = emptyList()
        _agentState.value = AgentState.IDLE
        _micLevel.value = 0f
        _remoteLevel.value = 0f
        _agoraAudio.value = false
        _isMuted.value = !micGranted
        _isPaused.value = false
        _canResume.value = false
        _voiceConnected.value = false
        _liveCaption.value = ""
        deviceMode = false

        val prefs = settingsStore.settings.first()
        val resumeHistory = resumeFrom.mapNotNull { m ->
            val role = when (m.role) {
                com.quietstack.voicetrade.domain.model.Role.USER -> "user"
                com.quietstack.voicetrade.domain.model.Role.AGENT -> "assistant"
                else -> null
            }
            val text = m.text?.trim()
            if (role != null && !text.isNullOrBlank()) com.quietstack.voicetrade.data.remote.dto.ResumeTurnDto(role, text) else null
        }.takeIf { it.isNotEmpty() }
        val info = apiCall {
            gateway.api().startSession(
                StartSessionRequest(talkLanguage(), prefs.voice.code, prefs.speechRate, resumeHistory),
            ).toDomain()
        }.getOrElse { return Result.failure(it) }
        session = info
        store.activeSessionId = info.sessionId
        if (resumeHistory != null) _messages.value = resumeFrom // show the earlier turns right away, not just new ones

        // The server has no Agora voice configured (blank app id): run the session as text only.
        if (info.appId.isNotBlank()) {
            val engine = gateway.rtc()
            rtc = engine
            if (micGranted) service.start()
            rtcJob = scope.launch { engine.events.collect(::onRtcEvent) }

            val joined = engine.join(info.appId, info.channel, info.token, info.uid, publishMic = micGranted)
            if (joined.isFailure) {
                teardown(info, engine)
                return Result.failure(joined.exceptionOrNull().let { it?.asAppError() ?: IllegalStateException() })
            }
            _voiceConnected.value = true
            _agoraAudio.value = true
        } else if (micGranted && deviceVoice.isAvailable()) {
            // No Agora on the server: talk through the phone's own speech recognition and voice instead.
            service.start()
            deviceMode = true
            _voiceConnected.value = true
        }

        startedAtMillis = clock.millis()
        _isLive.value = true
        _agentState.value = AgentState.LISTENING
        eventsJob = scope.launch {
            gateway.socket().connect(info.sessionId, info.wsUrl).collect(::onSessionEvent)
        }
        if (deviceMode) startVoiceLoop(prefs)
        Result.success(Unit)
    }

    override suspend fun end(): Result<Unit> = lifecycleLock.withLock {
        val info = session ?: return Result.success(Unit)
        val engine = rtc
        val result = teardown(info, engine)
        Result.success(result)
    }

    private suspend fun teardown(info: SessionInfo, engine: RtcDataSource?) {
        voiceLoop?.cancel()
        voiceLoop = null
        pendingReply?.cancel()
        pendingReply = null
        deviceVoice.stopSpeaking()
        deviceMode = false
        _liveCaption.value = ""
        eventsJob?.cancel()
        rtcJob?.cancel()
        eventsJob = null
        rtcJob = null
        runCatching { engine?.leave() }
        service.stop()
        _isLive.value = false
        _agentState.value = AgentState.IDLE
        _micLevel.value = 0f
        _remoteLevel.value = 0f
        _agoraAudio.value = false
        _voiceConnected.value = false
        apiCall { gateway.api().endSession(info.sessionId) }.onFailure { Timber.w(it, "endSession failed") }
        store.activeSessionId = null
        session = null
        rtc = null
    }

    override fun interrupt(): Boolean {
        if (!deviceMode || _agentState.value != AgentState.SPEAKING) return false
        deviceVoice.stopSpeaking()
        return true
    }

    /** listen -> send what was said -> wait for Mira's reply -> speak it -> listen again. */
    private fun startVoiceLoop(prefs: AppSettings) {
        val tag = if (talkLanguage() == "hinglish") "hi-IN" else "en-IN"
        voiceLoop = scope.launch {
            speakAloud("Hi, I'm Mira. What would you like to know?", prefs.speechRate, addToTranscript = true)
            var silentRounds = 0
            while (isActive && _isLive.value) {
                if (_isMuted.value) {
                    silentRounds = 0 // unmuting starts a fresh countdown
                    setAgentState(AgentState.IDLE)
                    delay(300)
                    continue
                }
                setAgentState(AgentState.LISTENING)
                val heard = listenOnce(tag)
                _liveCaption.value = ""
                if (heard == null) {
                    if (++silentRounds >= MAX_SILENT_ROUNDS) {
                        _isMuted.value = true
                        addSystem("Paused because I didn't hear anything. Tap the orb to keep talking.", dedupe = true)
                    }
                    delay(200)
                    continue
                }
                silentRounds = 0
                setAgentState(AgentState.THINKING)
                val reply = CompletableDeferred<String>().also { pendingReply = it }
                val sent = sendText(heard)
                if (sent.isFailure) {
                    pendingReply = null
                    addSystem("I couldn't reach the server. Check your connection and try again.")
                    continue
                }
                val text = withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }
                pendingReply = null
                if (text == null) addSystem("That took too long. Please try again.") else speakAloud(text, prefs.speechRate)
                delay(250) // let the speaker go quiet so the microphone doesn't hear Mira's last word
            }
        }
    }

    private suspend fun listenOnce(tag: String): String? {
        var heard: String? = null
        deviceVoice.listen(tag).takeWhile { !_isMuted.value }.collect { ev ->
            when (ev) {
                is ListenEvent.Partial -> _liveCaption.value = ev.text
                is ListenEvent.Final -> heard = ev.text
                is ListenEvent.Level -> _micLevel.value = ev.level
                is ListenEvent.Failed -> if (ev.fatal) {
                    _isMuted.value = true
                    addSystem("I can't use the microphone. Allow it in system settings, or type instead.")
                }
            }
        }
        return heard?.takeIf { it.isNotBlank() }
    }

    private suspend fun speakAloud(text: String, rate: Float, addToTranscript: Boolean = false) {
        if (addToTranscript) {
            upsert("local-${++counter}") { ConversationMessage("local-$counter", Role.AGENT, text, null, true, clock.instant()) }
        }
        setAgentState(AgentState.SPEAKING)
        deviceVoice.speak(text, rate)
    }

    /** A live order preview keeps the orb amber (waiting for a yes) instead of plain "listening". */
    private fun setAgentState(state: AgentState) {
        _agentState.value = if (state == AgentState.LISTENING && hasActivePreview()) AgentState.AWAITING_CONFIRMATION else state
    }

    override suspend fun pause() {
        val id = session?.sessionId ?: return
        _isPaused.value = true // instantly; the server confirms with a playback event
        _canResume.value = true
        if (deviceMode) deviceVoice.stopSpeaking() else apiCall { gateway.api().pauseSession(id) }.onFailure { Timber.w(it, "pause failed") }
    }

    override suspend fun resumeAnswer() {
        val id = session?.sessionId ?: return
        _isPaused.value = false
        _canResume.value = false
        if (!deviceMode) apiCall { gateway.api().resumeSession(id) }.onFailure { Timber.w(it, "resume failed") }
    }

    override suspend fun replayLastAnswer() {
        val id = session?.sessionId ?: return
        _isPaused.value = false
        _canResume.value = false
        if (!deviceMode) apiCall { gateway.api().replaySession(id) }.onFailure { Timber.w(it, "replay failed") }
    }

    override suspend fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        rtc?.setMicMuted(muted)
    }

    override suspend fun sendText(text: String): Result<Unit> {
        val info = session ?: return Result.failure(IllegalStateException("No active session"))
        return apiCall { gateway.api().sendText(info.sessionId, TextRequest(text)) }
    }

    override fun markPreview(previewId: String, state: PreviewState) {
        _messages.update { list ->
            list.map { msg ->
                val card = msg.card
                if (card is ActionCard.PreviewCard && card.preview.previewId == previewId && card.state == PreviewState.ACTIVE) {
                    msg.copy(card = card.copy(state = state))
                } else {
                    msg
                }
            }
        }
        if (_agentState.value == AgentState.AWAITING_CONFIRMATION) _agentState.value = AgentState.LISTENING
    }

    override fun upsertOrder(order: Order) {
        upsert("order-${order.orderId}") { existing ->
            ConversationMessage(
                id = "order-${order.orderId}",
                role = Role.AGENT,
                text = null,
                card = ActionCard.StatusCard(order),
                isFinal = true,
                timestamp = existing?.timestamp ?: clock.instant(),
            )
        }
    }

    override suspend fun cleanupOrphan() {
        val orphan = store.activeSessionId ?: return
        if (session != null) return
        apiCall { gateway.api().endSession(orphan) }
        store.activeSessionId = null
    }

    // ---- event handling ---------------------------------------------------------------------------

    private fun onSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Transcript -> {
                upsert("t-${event.messageId}") { existing ->
                    ConversationMessage("t-${event.messageId}", event.role, event.text, null, event.isFinal, existing?.timestamp ?: clock.instant())
                }
                if (deviceMode && event.role == Role.AGENT && event.isFinal && event.text.isNotBlank()) {
                    val waiting = pendingReply
                    if (waiting != null) waiting.complete(event.text)
                    else scope.launch { speakAloud(event.text, 1.0f) } // e.g. a limit order just filled
                }
            }
            is SessionEvent.AgentStateChanged -> if (!deviceMode) {
                // A live preview keeps the orb amber until it is confirmed, rejected or expires.
                _agentState.value = if (hasActivePreview() && event.state == AgentState.LISTENING) AgentState.AWAITING_CONFIRMATION else event.state
            }
            is SessionEvent.PlaybackChanged -> {
                _isPaused.value = event.paused
                _canResume.value = event.resumable
            }
            is SessionEvent.CardShown -> upsert(event.messageId) { existing ->
                ConversationMessage(event.messageId, Role.AGENT, null, event.card, true, existing?.timestamp ?: clock.instant())
            }
            is SessionEvent.PreviewCreated -> {
                _messages.value.mapNotNull { (it.card as? ActionCard.PreviewCard)?.preview?.previewId }
                    .filter { it != event.preview.previewId }
                    .forEach { markPreview(it, PreviewState.REPLACED) }
                val id = "preview-${event.preview.previewId}"
                upsert(id) { existing ->
                    ConversationMessage(id, Role.AGENT, null, ActionCard.PreviewCard(event.preview), true, existing?.timestamp ?: clock.instant())
                }
                _agentState.value = AgentState.AWAITING_CONFIRMATION
            }
            is SessionEvent.PreviewClosed -> markPreview(event.previewId, event.state)
            is SessionEvent.OrderUpdated -> upsertOrder(event.order)
            is SessionEvent.BrokerStatusChanged -> {
                _brokerStatus.value = event.status
                if (!event.status.authenticated) addSystem("Your IBKR session has ended. Trading is paused.")
            }
            is SessionEvent.WatchlistChanged -> Unit // the watchlist screen reads it from the server
            is SessionEvent.Failure -> upsert("err-${++counter}") {
                ConversationMessage("err-$counter", Role.SYSTEM, null, ActionCard.ErrorCard(event.message, event.retryable), true, clock.instant())
            }
            is SessionEvent.System -> addSystem(event.text)
            SessionEvent.Disconnected -> if (_isLive.value) addSystem("Connection lost. Reconnecting…", dedupe = true)
        }
    }

    private fun onRtcEvent(event: RtcEvent) {
        when (event) {
            is RtcEvent.Volume -> {
                _remoteLevel.value = event.remote
                _micLevel.value = if (event.remote > event.local) event.remote else event.local
            }
            is RtcEvent.AgentLeft -> if (_isLive.value) {
                addSystem("The voice assistant left the session.")
                _agentState.value = AgentState.IDLE
            }
            is RtcEvent.TokenWillExpire -> renewToken()
            is RtcEvent.ConnectionLost -> if (event.reconnecting) addSystem("Reconnecting…", dedupe = true)
            is RtcEvent.Error -> Timber.w("RTC error %d", event.code)
            RtcEvent.AudioFocusLost -> {
                rtc?.setMicMuted(true)
                addSystem("Paused for call")
            }
            RtcEvent.AudioFocusRegained -> {
                rtc?.setMicMuted(_isMuted.value)
                addSystem("Resumed")
            }
            is RtcEvent.Joined, is RtcEvent.AgentJoined -> Unit
        }
    }

    private fun renewToken() {
        val info = session ?: return
        scope.launch {
            apiCall { gateway.api().renewToken(info.sessionId) }.onSuccess { rtc?.renewToken(it.token) }
        }
    }

    private fun hasActivePreview(): Boolean = _messages.value.any {
        (it.card as? ActionCard.PreviewCard)?.state == PreviewState.ACTIVE
    }

    private fun addSystem(text: String, dedupe: Boolean = false) {
        _messages.update { list ->
            if (dedupe && list.lastOrNull()?.text == text) list
            else list + ConversationMessage("sys-${++counter}", Role.SYSTEM, text, null, true, clock.instant())
        }
    }

    private fun upsert(id: String, build: (ConversationMessage?) -> ConversationMessage) {
        _messages.update { list ->
            val index = list.indexOfFirst { it.id == id }
            val next = build(list.getOrNull(index))
            if (index >= 0) list.toMutableList().also { it[index] = next } else list + next
        }
    }

    private companion object {
        const val MAX_SILENT_ROUNDS = 9 // ~5 s each, so about a minute of quiet before pausing (PRD idle timeout)
        const val REPLY_TIMEOUT_MS = 30_000L
    }
}
