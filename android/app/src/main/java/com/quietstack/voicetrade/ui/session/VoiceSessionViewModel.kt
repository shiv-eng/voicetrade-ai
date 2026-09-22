package com.quietstack.voicetrade.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.core.util.BiometricPolicy
import com.quietstack.voicetrade.domain.model.AgentState
import com.quietstack.voicetrade.domain.model.ConversationMessage
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.PreviewKind
import com.quietstack.voicetrade.domain.model.latestActivePreview
import com.quietstack.voicetrade.domain.usecase.ConfirmOrderUseCase
import com.quietstack.voicetrade.domain.usecase.EndSessionUseCase
import com.quietstack.voicetrade.domain.usecase.ExpirePreviewUseCase
import com.quietstack.voicetrade.domain.usecase.ObserveAgentStateUseCase
import com.quietstack.voicetrade.domain.usecase.ObserveConversationUseCase
import com.quietstack.voicetrade.domain.usecase.ObserveSessionStatusUseCase
import com.quietstack.voicetrade.domain.usecase.ObserveSettingsUseCase
import com.quietstack.voicetrade.domain.usecase.RejectOrderUseCase
import com.quietstack.voicetrade.domain.usecase.RiskLimitsUseCase
import com.quietstack.voicetrade.domain.usecase.SendTextMessageUseCase
import com.quietstack.voicetrade.domain.usecase.ResumeAnswerUseCase
import com.quietstack.voicetrade.domain.usecase.TogglePauseUseCase
import com.quietstack.voicetrade.domain.usecase.StartSessionUseCase
import com.quietstack.voicetrade.domain.usecase.ToggleMuteUseCase
import com.quietstack.voicetrade.domain.usecase.UpdateWatchlistUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data object Connected : Connection
    data class Error(val error: AppError) : Connection
}

data class VoiceSessionUiState(
    val connection: Connection = Connection.Idle,
    val agentState: AgentState = AgentState.IDLE,
    val micLevel: Float = 0f,
    /** Mira's voice is actually coming out of the speaker right now (not just generated on the server). */
    val agentAudible: Boolean = false,
    /** Whether we can tell (Agora audio); with the phone's own voice `agentState == SPEAKING` is exact. */
    val agoraAudio: Boolean = false,
    /** Mira has already been heard in the current answer. */
    val spokeThisTurn: Boolean = false,
    val liveCaption: String = "",
    val isMuted: Boolean = false,
    val isPaused: Boolean = false,
    /** Mira was paused or cut off: offer to say the answer again. */
    val canResume: Boolean = false,
    val messages: List<ConversationMessage> = emptyList(),
    val activePreview: OrderPreview? = null,
    val previewSecondsLeft: Int = 0,
    val isConfirming: Boolean = false,
    val killSwitchOn: Boolean = false,
    val textOnly: Boolean = false,
    val micDenied: Boolean = false,
    val keyboardOpen: Boolean = false,
)

sealed interface VoiceSessionEvent {
    data class Start(val micGranted: Boolean, val prompt: String? = null, val resumeSessionId: Long? = null) : VoiceSessionEvent
    data object End : VoiceSessionEvent
    data object ToggleMute : VoiceSessionEvent
    data object TogglePause : VoiceSessionEvent
    data object ResumeAnswer : VoiceSessionEvent
    data object ToggleKeyboard : VoiceSessionEvent
    data class ConfirmPreview(val previewId: String) : VoiceSessionEvent
    data class BiometricPassed(val previewId: String) : VoiceSessionEvent
    data class RejectPreview(val previewId: String) : VoiceSessionEvent
    data class SendText(val text: String) : VoiceSessionEvent
    data class AddToWatchlist(val instrument: Instrument) : VoiceSessionEvent
    data object Retry : VoiceSessionEvent
}

sealed interface VoiceSessionEffect {
    data object Haptic : VoiceSessionEffect
    data class ShowSnackbar(val error: AppError? = null, val message: String? = null) : VoiceSessionEffect
    data object NavigateBack : VoiceSessionEffect
    data class RequestBiometric(val previewId: String) : VoiceSessionEffect
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class VoiceSessionViewModel @Inject constructor(
    private val startSession: StartSessionUseCase,
    private val endSession: EndSessionUseCase,
    private val toggleMute: ToggleMuteUseCase,
    private val togglePause: TogglePauseUseCase,
    private val resumeAnswer: ResumeAnswerUseCase,
    observeConversation: ObserveConversationUseCase,
    observeAgentState: ObserveAgentStateUseCase,
    private val sessionStatus: ObserveSessionStatusUseCase,
    private val confirmOrder: ConfirmOrderUseCase,
    private val rejectOrder: RejectOrderUseCase,
    private val expirePreview: ExpirePreviewUseCase,
    private val sendText: SendTextMessageUseCase,
    private val settings: ObserveSettingsUseCase,
    private val riskLimits: RiskLimitsUseCase,
    private val watchlist: UpdateWatchlistUseCase,
    private val observeHistory: com.quietstack.voicetrade.domain.usecase.ObserveHistoryUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceSessionUiState())
    val state: StateFlow<VoiceSessionUiState> = _state.asStateFlow()

    private val _effects = Channel<VoiceSessionEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private companion object {
        const val AUDIBLE_LEVEL = 0.03f
        const val AUDIBLE_HOLD_MS = 700L
    }

    private var wasLive = false
    private var leaving = false
    private var lastMicGranted = true
    private var lastResumeSessionId: Long? = null

    init {
        observeConversation()
            .onEach { msgs ->
                val active = msgs.latestActivePreview()
                _state.update {
                    it.copy(
                        messages = msgs,
                        activePreview = active,
                        isConfirming = if (active?.previewId != it.activePreview?.previewId) false else it.isConfirming,
                    )
                }
            }
            .launchIn(viewModelScope)
        observeAgentState().onEach { s ->
            _state.update {
                // A new answer is being prepared: forget that the last one was heard.
                it.copy(agentState = s, spokeThisTurn = if (s == AgentState.THINKING) false else it.spokeThisTurn)
            }
        }.launchIn(viewModelScope)
        sessionStatus.agoraAudio.onEach { a -> _state.update { it.copy(agoraAudio = a) } }.launchIn(viewModelScope)
        // Audible = Agora reports her voice above the noise floor; hold 700 ms so gaps between words don't flicker.
        sessionStatus.remoteLevel
            .map { it > AUDIBLE_LEVEL }
            .transformLatest { loud -> if (loud) emit(true) else { delay(AUDIBLE_HOLD_MS); emit(false) } }
            .distinctUntilChanged()
            .onEach { on -> _state.update { it.copy(agentAudible = on, spokeThisTurn = it.spokeThisTurn || on) } }
            .launchIn(viewModelScope)
        sessionStatus.micLevel.onEach { l -> _state.update { it.copy(micLevel = l) } }.launchIn(viewModelScope)
        sessionStatus.liveCaption.onEach { c -> _state.update { it.copy(liveCaption = c) } }.launchIn(viewModelScope)
        sessionStatus.isMuted.onEach { m -> _state.update { it.copy(isMuted = m) } }.launchIn(viewModelScope)
        sessionStatus.isPaused.onEach { p -> _state.update { it.copy(isPaused = p) } }.launchIn(viewModelScope)
        sessionStatus.canResume.onEach { c -> _state.update { it.copy(canResume = c) } }.launchIn(viewModelScope)
        settings.killSwitch.onEach { k -> _state.update { it.copy(killSwitchOn = k) } }.launchIn(viewModelScope)
        sessionStatus.isLive.onEach { live ->
            if (live) wasLive = true
            if (wasLive && !live) leave()
        }.launchIn(viewModelScope)
        startCountdown()
    }

    fun onEvent(e: VoiceSessionEvent) {
        when (e) {
            is VoiceSessionEvent.Start -> start(e.micGranted, e.prompt, e.resumeSessionId)
            VoiceSessionEvent.Retry -> {
                _state.update { it.copy(connection = Connection.Idle) }
                start(lastMicGranted, null, lastResumeSessionId)
            }
            VoiceSessionEvent.End -> viewModelScope.launch(NonCancellable) {
                endSession()
                leave()
            }
            VoiceSessionEvent.ToggleMute -> viewModelScope.launch { toggleMute() }
            VoiceSessionEvent.TogglePause -> viewModelScope.launch { togglePause() }
            VoiceSessionEvent.ResumeAnswer -> viewModelScope.launch { resumeAnswer() }
            VoiceSessionEvent.ToggleKeyboard -> _state.update { it.copy(keyboardOpen = !it.keyboardOpen) }
            is VoiceSessionEvent.ConfirmPreview -> requestConfirm(e.previewId)
            is VoiceSessionEvent.BiometricPassed -> confirm(e.previewId)
            is VoiceSessionEvent.RejectPreview -> viewModelScope.launch {
                rejectOrder(e.previewId).onFailure { err -> error(err) }
            }
            is VoiceSessionEvent.SendText -> viewModelScope.launch {
                sendText(e.text).onFailure { err -> error(err) }
            }
            is VoiceSessionEvent.AddToWatchlist -> viewModelScope.launch {
                watchlist.add(e.instrument)
                    .onSuccess { _effects.send(VoiceSessionEffect.ShowSnackbar(message = "Added ${e.instrument.symbol} to your watchlist")) }
                    .onFailure { err -> error(err) }
            }
        }
    }

    private fun start(micGranted: Boolean, prompt: String?, resumeSessionId: Long? = null) {
        val current = _state.value.connection
        if (current is Connection.Connecting || current is Connection.Connected) return
        lastMicGranted = micGranted
        lastResumeSessionId = resumeSessionId
        _state.update { it.copy(connection = Connection.Connecting, textOnly = !micGranted, micDenied = !micGranted) }
        viewModelScope.launch {
            launch { riskLimits.load() }  // needed later, not before connecting
            val resumeFrom = resumeSessionId?.let { observeHistory.messages(it).first() }.orEmpty()
            startSession(micGranted, resumeFrom)
                .onSuccess {
                    // Text-only also when the server has no Agora voice configured.
                    val voice = sessionStatus.voiceConnected.value
                    _state.update { s -> s.copy(connection = Connection.Connected, textOnly = !(micGranted && voice)) }
                    _effects.send(VoiceSessionEffect.Haptic)
                    if (!prompt.isNullOrBlank()) sendText(prompt).onFailure { err -> error(err) }
                }
                .onFailure { err -> _state.update { it.copy(connection = Connection.Error(err.asAppError())) } }
        }
    }

    private fun requestConfirm(previewId: String) {
        val preview = _state.value.activePreview?.takeIf { it.previewId == previewId } ?: return
        if (_state.value.isConfirming) return
        viewModelScope.launch {
            val biometricOn = settings.app.first().biometricOn
            if (preview.kind == PreviewKind.PLACE && BiometricPolicy.requiresAuth(preview.estimatedValue, biometricOn)) {
                _effects.send(VoiceSessionEffect.RequestBiometric(previewId))
            } else {
                confirm(previewId)
            }
        }
    }

    private fun confirm(previewId: String) {
        if (_state.value.isConfirming) return
        _state.update { it.copy(isConfirming = true) }
        viewModelScope.launch {
            confirmOrder(previewId)
                .onSuccess { _effects.send(VoiceSessionEffect.Haptic) }
                .onFailure { err -> error(err) }
            _state.update { it.copy(isConfirming = false) }
        }
    }

    private suspend fun error(t: Throwable) {
        _effects.send(VoiceSessionEffect.ShowSnackbar(error = t.asAppError()))
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        _effects.trySend(VoiceSessionEffect.NavigateBack)
    }

    private fun startCountdown() {
        viewModelScope.launch {
            while (true) {
                delay(500)
                val preview = _state.value.activePreview
                val left = if (preview == null) 0 else {
                    val millis = Duration.between(clock.instant(), preview.expiresAt).toMillis()
                    ((millis + 999) / 1000).toInt().coerceAtLeast(0)
                }
                if (preview != null && left == 0) expirePreview(preview.previewId)
                _state.update { if (it.previewSecondsLeft == left) it else it.copy(previewSecondsLeft = left) }
            }
        }
    }
}
