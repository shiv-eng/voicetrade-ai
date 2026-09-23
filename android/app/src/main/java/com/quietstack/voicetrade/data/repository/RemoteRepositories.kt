package com.quietstack.voicetrade.data.repository

import com.quietstack.voicetrade.data.remote.dto.AuthResponse
import com.quietstack.voicetrade.domain.model.UserProfile
import com.quietstack.voicetrade.domain.repository.AuthRepository
import com.quietstack.voicetrade.data.local.prefs.SecureTokenStore
import com.quietstack.voicetrade.data.local.prefs.SettingsDataStore
import com.quietstack.voicetrade.data.mapper.toDomain
import com.quietstack.voicetrade.data.remote.apiCall
import com.quietstack.voicetrade.data.remote.dto.DeviceTokenRequest
import com.quietstack.voicetrade.data.remote.dto.GoogleLoginRequest
import com.quietstack.voicetrade.domain.model.AccountSummary
import com.quietstack.voicetrade.domain.model.AppSettings
import com.quietstack.voicetrade.domain.model.BrokerStatus
import com.quietstack.voicetrade.domain.model.ConfirmResult
import com.quietstack.voicetrade.domain.model.ConnectionConfig
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.Order
import com.quietstack.voicetrade.domain.model.OrderFilter
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.Pnl
import com.quietstack.voicetrade.domain.model.Position
import com.quietstack.voicetrade.domain.model.Quote
import com.quietstack.voicetrade.domain.repository.MarketRepository
import com.quietstack.voicetrade.domain.repository.OrderRepository
import com.quietstack.voicetrade.domain.repository.PortfolioRepository
import com.quietstack.voicetrade.domain.repository.PushRepository
import com.quietstack.voicetrade.domain.repository.SettingsRepository
import com.quietstack.voicetrade.domain.repository.WatchlistRepository
import com.quietstack.voicetrade.domain.model.WatchRow
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: SettingsDataStore,
    private val store: SecureTokenStore,
    private val gateway: BackendGateway,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.settings
    override val connection: Flow<ConnectionConfig> = store.connection

    override suspend fun update(transform: (AppSettings) -> AppSettings) = dataStore.update(transform)
}

@Singleton
class MarketRepositoryImpl @Inject constructor(private val gateway: BackendGateway) : MarketRepository {
    override suspend fun brokerStatus(): Result<BrokerStatus> = apiCall { gateway.api().brokerStatus().toDomain() }
    override suspend fun search(query: String): Result<List<Instrument>> =
        apiCall { gateway.api().search(query).map { it.toDomain() } }

    override suspend fun quote(conid: Long): Result<Quote> = apiCall { gateway.api().quote(conid).toDomain() }
}

@Singleton
class PortfolioRepositoryImpl @Inject constructor(private val gateway: BackendGateway) : PortfolioRepository {
    override suspend fun account(): Result<AccountSummary> = apiCall { gateway.api().account().toDomain() }
    override suspend fun positions(): Result<List<Position>> = apiCall { gateway.api().positions().map { it.toDomain() } }
    override suspend fun pnl(): Result<Pnl> = apiCall { gateway.api().pnl().toDomain() }
}

@Singleton
class OrderRepositoryImpl @Inject constructor(private val gateway: BackendGateway) : OrderRepository {
    override suspend fun orders(filter: OrderFilter): Result<List<Order>> =
        apiCall { gateway.api().orders(filter.apiValue).map { it.toDomain() } }

    override suspend fun previewCancel(orderId: String): Result<OrderPreview> =
        apiCall { gateway.api().previewCancel(orderId).toDomain() }

    override suspend fun confirm(previewId: String): Result<ConfirmResult> =
        apiCall { gateway.api().confirmPreview(previewId).let { ConfirmResult(it.orderId, it.status) } }

    override suspend fun reject(previewId: String): Result<Unit> = apiCall { gateway.api().rejectPreview(previewId) }
}

@Singleton
class WatchlistRepositoryImpl @Inject constructor(private val gateway: BackendGateway) : WatchlistRepository {
    override suspend fun list(): Result<List<WatchRow>> = apiCall { gateway.api().watchlist().map { it.toDomain() } }
    override suspend fun add(instrument: Instrument): Result<Unit> = apiCall { gateway.api().watchlistAdd(instrument.conid) }
    override suspend fun remove(conid: Long): Result<Unit> = apiCall { gateway.api().watchlistRemove(conid) }
    override suspend fun move(conid: Long, up: Boolean): Result<Unit> = apiCall { gateway.api().watchlistMove(conid, up) }
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val gateway: BackendGateway,
    private val store: SecureTokenStore,
) : AuthRepository {

    override suspend fun signInWithGoogle(idToken: String): Result<UserProfile> =
        apiCall { gateway.api().googleLogin(GoogleLoginRequest(idToken)) }.map(::accept)

    override suspend fun signInAsGuest(): Result<UserProfile> =
        apiCall { gateway.api().guestLogin(emptyMap()) }.map(::accept)

    override suspend fun signOut() = store.clear()

    private fun accept(r: AuthResponse): UserProfile {
        val p = UserProfile(r.profile.userId, r.profile.email, r.profile.name.ifBlank { r.profile.email ?: "You" }, r.profile.picture)
        store.signIn(r.token, p)
        return p
    }
}

@Singleton
class PushRepositoryImpl @Inject constructor(private val gateway: BackendGateway) : PushRepository {
    override suspend fun registerToken(token: String): Result<Unit> =
        apiCall { gateway.api().registerDevice(DeviceTokenRequest(token)) }
}
