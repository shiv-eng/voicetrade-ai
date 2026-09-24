package com.quietstack.voicetrade.domain.usecase

import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.domain.model.AccountSummary
import com.quietstack.voicetrade.domain.model.AppSettings
import com.quietstack.voicetrade.domain.model.ConnectionConfig
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.Order
import com.quietstack.voicetrade.domain.model.OrderFilter
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.Pnl
import com.quietstack.voicetrade.domain.model.Position
import com.quietstack.voicetrade.domain.model.SessionSummary
import com.quietstack.voicetrade.domain.model.WatchRow
import com.quietstack.voicetrade.domain.model.UserProfile
import com.quietstack.voicetrade.domain.repository.AuthRepository
import com.quietstack.voicetrade.domain.repository.HistoryRepository
import com.quietstack.voicetrade.domain.repository.MarketRepository
import com.quietstack.voicetrade.domain.repository.OrderRepository
import com.quietstack.voicetrade.domain.repository.PortfolioRepository
import com.quietstack.voicetrade.domain.repository.SettingsRepository
import com.quietstack.voicetrade.domain.repository.WatchlistRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

data class PortfolioSnapshot(
    val summary: AccountSummary,
    val positions: List<Position>,
    val pnl: Pnl?,
)

class GetPortfolioUseCase @Inject constructor(private val portfolio: PortfolioRepository) {
    /** Account and positions are required; daily P&L is best effort. */
    suspend operator fun invoke(): Result<PortfolioSnapshot> = coroutineScope {
        val account = async { portfolio.account() }
        val positions = async { portfolio.positions() }
        val pnl = async { portfolio.pnl() }
        val a = account.await().getOrElse { return@coroutineScope Result.failure(it.asAppError()) }
        val p = positions.await().getOrElse { return@coroutineScope Result.failure(it.asAppError()) }
        Result.success(PortfolioSnapshot(a, p, pnl.await().getOrNull()))
    }
}

class SearchInstrumentUseCase @Inject constructor(private val market: MarketRepository) {
    suspend operator fun invoke(query: String): Result<List<Instrument>> =
        if (query.isBlank()) Result.success(emptyList()) else market.search(query.trim())
}

class GetOrdersUseCase @Inject constructor(private val orders: OrderRepository) {
    suspend operator fun invoke(filter: OrderFilter): Result<List<Order>> = orders.orders(filter)
}

class PreviewCancelOrderUseCase @Inject constructor(private val orders: OrderRepository) {
    suspend operator fun invoke(orderId: String): Result<OrderPreview> = orders.previewCancel(orderId)
}

class GetWatchlistUseCase @Inject constructor(private val watchlist: WatchlistRepository) {
    suspend operator fun invoke(): Result<List<WatchRow>> = watchlist.list()
}

class UpdateWatchlistUseCase @Inject constructor(private val watchlist: WatchlistRepository) {
    suspend fun add(instrument: Instrument): Result<Unit> = watchlist.add(instrument)
    suspend fun remove(conid: Long): Result<Unit> = watchlist.remove(conid)
    suspend fun move(conid: Long, up: Boolean): Result<Unit> = watchlist.move(conid, up)
}

class ObserveHistoryUseCase @Inject constructor(private val history: HistoryRepository) {
    fun sessions(): Flow<List<SessionSummary>> = history.observeSessions()
    fun messages(sessionId: Long) = history.observeMessages(sessionId)
}

class ManageHistoryUseCase @Inject constructor(private val history: HistoryRepository) {
    suspend fun delete(sessionId: Long) = history.delete(sessionId)
    suspend fun deleteAll() = history.deleteAll()
}

class ObserveSettingsUseCase @Inject constructor(private val settings: SettingsRepository) {
    val app: Flow<AppSettings> get() = settings.settings
    val connection: Flow<ConnectionConfig> get() = settings.connection
}

class UpdateSettingsUseCase @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(transform: (AppSettings) -> AppSettings) = settings.update(transform)
}

class SignInUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend fun google(idToken: String): Result<UserProfile> = auth.signInWithGoogle(idToken)
    suspend fun guest(): Result<UserProfile> = auth.signInAsGuest()
}

class SignOutUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke() = auth.signOut()
}

class RegisterPushTokenUseCase @Inject constructor(private val push: com.quietstack.voicetrade.domain.repository.PushRepository) {
    suspend operator fun invoke(token: String) = push.registerToken(token)
}

class ObserveNetworkUseCase @Inject constructor(private val monitor: com.quietstack.voicetrade.core.util.NetworkMonitor) {
    operator fun invoke(): Flow<Boolean> = monitor.isOnline
}

/** Confirm or discard a preview that is not part of a live voice session (e.g. cancelling from the Orders screen). */
class ResolvePreviewUseCase @Inject constructor(private val orders: OrderRepository) {
    suspend fun confirm(previewId: String) = orders.confirm(previewId)
    suspend fun reject(previewId: String) = orders.reject(previewId)
}
