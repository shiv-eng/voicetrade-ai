package com.quietstack.voicetrade.domain.repository

import com.quietstack.voicetrade.domain.model.AccountSummary
import com.quietstack.voicetrade.domain.model.AgentState
import com.quietstack.voicetrade.domain.model.AppSettings
import com.quietstack.voicetrade.domain.model.BrokerStatus
import com.quietstack.voicetrade.domain.model.ConfirmResult
import com.quietstack.voicetrade.domain.model.ConnectionConfig
import com.quietstack.voicetrade.domain.model.ConversationMessage
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.Order
import com.quietstack.voicetrade.domain.model.OrderFilter
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.Pnl
import com.quietstack.voicetrade.domain.model.Position
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.SessionSummary
import com.quietstack.voicetrade.domain.model.UserProfile
import com.quietstack.voicetrade.domain.model.WatchRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    val connection: Flow<ConnectionConfig>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface AuthRepository {
    suspend fun signInWithGoogle(idToken: String): Result<UserProfile>
    /** Debug builds only: an anonymous account, so the app can be tried without Google credentials. */
    suspend fun signInAsGuest(): Result<UserProfile>
    suspend fun signOut()
}

interface MarketRepository {
    suspend fun search(query: String): Result<List<Instrument>>
}

interface PortfolioRepository {
    suspend fun account(): Result<AccountSummary>
    suspend fun positions(): Result<List<Position>>
    suspend fun pnl(): Result<Pnl>
}

interface OrderRepository {
    suspend fun orders(filter: OrderFilter): Result<List<Order>>
    suspend fun previewCancel(orderId: String): Result<OrderPreview>
    suspend fun confirm(previewId: String): Result<ConfirmResult>
    suspend fun reject(previewId: String): Result<Unit>
}

interface WatchlistRepository {
    /** The user's watchlist with live quotes, from the server (voice edits land there too). */
    suspend fun list(): Result<List<WatchRow>>
    suspend fun add(instrument: Instrument): Result<Unit>
    suspend fun remove(conid: Long): Result<Unit>
    suspend fun move(conid: Long, up: Boolean): Result<Unit>

    companion object {
        const val MAX_ITEMS = 20
    }
}

interface HistoryRepository {
    fun observeSessions(): Flow<List<SessionSummary>>
    fun observeMessages(sessionId: Long): Flow<List<ConversationMessage>>
    suspend fun save(startedAtMillis: Long, endedAtMillis: Long, messages: List<ConversationMessage>)
    suspend fun delete(sessionId: Long)
    suspend fun deleteAll()
}

interface VoiceSessionRepository {
    val messages: StateFlow<List<ConversationMessage>>
    val agentState: StateFlow<AgentState>
    val micLevel: StateFlow<Float>
    /** How loud Mira's voice is right now (0..1). Only meaningful when the audio comes through Agora. */
    val remoteLevel: StateFlow<Float>
    /** True when Mira's voice comes from Agora (so we can tell when she is actually audible). */
    val agoraAudio: StateFlow<Boolean>
    val isMuted: StateFlow<Boolean>
    /** Mira is paused: silent until the user resumes. */
    val isPaused: StateFlow<Boolean>
    /** Her last answer was paused or cut off, so she can say it again from the start. */
    val canResume: StateFlow<Boolean>
    val isLive: StateFlow<Boolean>
    /** What the user is saying right now (partial speech-to-text), shown as a live caption. */
    val liveCaption: StateFlow<String>
    /** False when the session is text-only (no microphone, or the server has no Agora voice configured). */
    val voiceConnected: StateFlow<Boolean>
    val brokerStatus: StateFlow<BrokerStatus?>
    val startedAtMillis: Long?

    /** [resumeFrom] seeds Mira with a past conversation (user/agent turns only) so the user can pick up where they left off. */
    suspend fun start(micGranted: Boolean, resumeFrom: List<ConversationMessage> = emptyList()): Result<Unit>
    suspend fun end(): Result<Unit>
    /** Stops Mira mid-sentence (barge-in). Returns true if something was actually interrupted. */
    fun interrupt(): Boolean
    suspend fun setMuted(muted: Boolean)
    suspend fun pause()
    /** Leaves the pause and just starts listening again; nothing is repeated. */
    suspend fun resumeAnswer()
    /** An explicit ask to hear the last answer again, from the start. */
    suspend fun replayLastAnswer()
    suspend fun sendText(text: String): Result<Unit>
    fun markPreview(previewId: String, state: PreviewState)
    fun upsertOrder(order: Order)

    /** Ends an agent left behind by a killed process (session id persisted at start). */
    suspend fun cleanupOrphan()
}

/** Price charts, company overviews and IPO lists (the "explore" side of the app). */
interface ResearchRepository {
    suspend fun chart(conid: Long, period: String): Result<com.quietstack.voicetrade.domain.model.ChartData>
    suspend fun overview(conid: Long): Result<com.quietstack.voicetrade.domain.model.CompanyOverview>
    suspend fun ipos(market: String): Result<com.quietstack.voicetrade.domain.model.IpoList>
    suspend fun ipoDetail(symbol: String, series: String, name: String?): Result<com.quietstack.voicetrade.domain.model.IpoDetail>
    suspend fun marketOverview(): Result<com.quietstack.voicetrade.domain.model.MarketOverview>
    suspend fun portfolioHistory(currency: String, days: Int): Result<com.quietstack.voicetrade.domain.model.PortfolioHistory>
    suspend fun briefing(lang: String): Result<com.quietstack.voicetrade.domain.model.Briefing>
}

/** Price alerts: created by voice or from a stock page, checked on the server, delivered as notifications. */
interface AlertsRepository {
    suspend fun list(): Result<List<com.quietstack.voicetrade.domain.model.PriceAlert>>
    suspend fun add(conid: Long, target: String, direction: String?): Result<Long>
    suspend fun cancel(id: Long): Result<Unit>
    suspend fun pending(): Result<List<com.quietstack.voicetrade.domain.model.PriceAlert>>
    suspend fun ack(ids: List<Long>): Result<Unit>
}

/** Keeps the process alive and shows the "listening" notification during a session. */
interface SessionServiceController {
    fun start()
    fun stop()
}

/** Tells the backend where to push this phone's price alerts, so it doesn't have to wait for the poll. */
interface PushRepository {
    suspend fun registerToken(token: String): Result<Unit>
}
