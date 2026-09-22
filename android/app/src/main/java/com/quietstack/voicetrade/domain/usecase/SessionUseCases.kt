package com.quietstack.voicetrade.domain.usecase

import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.domain.model.AgentState
import com.quietstack.voicetrade.domain.model.ConfirmResult
import com.quietstack.voicetrade.domain.model.ConversationMessage
import com.quietstack.voicetrade.domain.model.Order
import com.quietstack.voicetrade.domain.model.OrderStatus
import com.quietstack.voicetrade.domain.model.PreviewKind
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.latestActivePreview
import com.quietstack.voicetrade.domain.repository.HistoryRepository
import com.quietstack.voicetrade.domain.repository.OrderRepository
import com.quietstack.voicetrade.domain.repository.VoiceSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import javax.inject.Inject

/** Mic permission is checked by the UI; without it the session runs in text-only mode. */
class StartSessionUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    suspend operator fun invoke(micGranted: Boolean, resumeFrom: List<ConversationMessage> = emptyList()): Result<Unit> =
        session.start(micGranted, resumeFrom)
}

class EndSessionUseCase @Inject constructor(
    private val session: VoiceSessionRepository,
    private val history: HistoryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Result<Unit> {
        val startedAt = session.startedAtMillis
        val transcript = session.messages.value
        val result = session.end()
        if (startedAt != null && transcript.any { it.isFinal || it.card != null }) {
            history.save(startedAt, clock.millis(), transcript.filter { it.isFinal || it.card != null })
        }
        return result
    }
}

class ToggleMuteUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    /** Tapping while Mira is speaking interrupts her; otherwise it mutes or unmutes the microphone. */
    suspend operator fun invoke() {
        if (!session.interrupt()) session.setMuted(!session.isMuted.value)
    }
}

/** Pause silences Mira; tapping play again just goes back to listening, it never repeats anything on its own. */
class TogglePauseUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    suspend operator fun invoke() {
        if (session.isPaused.value) session.resumeAnswer() else session.pause()
    }
}

/** The explicit "say it again" ask: the chip after an interruption, or the resume/repeat voice command. */
class ResumeAnswerUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    suspend operator fun invoke() = session.replayLastAnswer()
}

class ObserveConversationUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    operator fun invoke(): StateFlow<List<ConversationMessage>> = session.messages
}

class ObserveAgentStateUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    operator fun invoke(): Flow<AgentState> = session.agentState
}

class SendTextMessageUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    suspend operator fun invoke(text: String): Result<Unit> =
        if (text.isBlank()) Result.success(Unit) else session.sendText(text.trim())
}

/**
 * Client-side mirror of the backend's safety gate: only a live, unexpired preview can be confirmed,
 * and the backend still re-checks everything (expiry, price drift, kill switch, risk limits).
 */
class ConfirmOrderUseCase @Inject constructor(
    private val session: VoiceSessionRepository,
    private val orders: OrderRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(previewId: String): Result<ConfirmResult> {
        val preview = session.messages.value.latestActivePreview()?.takeIf { it.previewId == previewId }
            ?: return Result.failure(AppError.PreviewExpired())
        if (!clock.instant().isBefore(preview.expiresAt)) {
            session.markPreview(previewId, PreviewState.EXPIRED)
            return Result.failure(AppError.PreviewExpired())
        }
        return orders.confirm(previewId)
            .onSuccess { result ->
                session.markPreview(previewId, PreviewState.CONFIRMED)
                if (preview.kind == PreviewKind.PLACE) {
                    session.upsertOrder(
                        Order(
                            orderId = result.orderId,
                            instrument = preview.instrument,
                            side = preview.side,
                            quantity = preview.quantity,
                            type = preview.type,
                            limitPrice = preview.limitPrice,
                            status = OrderStatus.Working,
                            updatedAt = clock.instant(),
                        ),
                    )
                }
            }
            .onFailure { e ->
                if (e is AppError.PreviewExpired || e is AppError.PriceDrift) {
                    session.markPreview(previewId, PreviewState.EXPIRED)
                }
            }
    }
}

class RejectOrderUseCase @Inject constructor(
    private val session: VoiceSessionRepository,
    private val orders: OrderRepository,
) {
    suspend operator fun invoke(previewId: String): Result<Unit> {
        session.markPreview(previewId, PreviewState.REJECTED)
        return orders.reject(previewId)
    }
}

/** Lets the UI drop a preview locally once its 60 s window has passed. */
class ExpirePreviewUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    operator fun invoke(previewId: String) = session.markPreview(previewId, PreviewState.EXPIRED)
}

/** Live flags for the session screen: mic level for the orb, mute, whether audio is connected. */
class ObserveSessionStatusUseCase @Inject constructor(private val session: VoiceSessionRepository) {
    val micLevel: StateFlow<Float> get() = session.micLevel
    val remoteLevel: StateFlow<Float> get() = session.remoteLevel
    val agoraAudio: StateFlow<Boolean> get() = session.agoraAudio
    val isMuted: StateFlow<Boolean> get() = session.isMuted
    val isPaused: StateFlow<Boolean> get() = session.isPaused
    val canResume: StateFlow<Boolean> get() = session.canResume
    val isLive: StateFlow<Boolean> get() = session.isLive
    val voiceConnected: StateFlow<Boolean> get() = session.voiceConnected
    val liveCaption: StateFlow<String> get() = session.liveCaption
}
