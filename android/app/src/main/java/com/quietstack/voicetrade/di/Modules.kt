package com.quietstack.voicetrade.di

import android.content.Context
import androidx.room.Room
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.quietstack.voicetrade.BuildConfig
import com.quietstack.voicetrade.core.common.ApplicationScope
import com.quietstack.voicetrade.core.common.IoDispatcher
import com.quietstack.voicetrade.data.local.db.AppDatabase
import com.quietstack.voicetrade.data.local.db.SessionDao
import com.quietstack.voicetrade.data.local.prefs.SecureTokenStore
import com.quietstack.voicetrade.data.remote.AppJson
import com.quietstack.voicetrade.data.remote.api.VoiceTradeApi
import com.quietstack.voicetrade.data.repository.AuthRepositoryImpl
import com.quietstack.voicetrade.data.repository.HistoryRepositoryImpl
import com.quietstack.voicetrade.data.repository.MarketRepositoryImpl
import com.quietstack.voicetrade.data.repository.OrderRepositoryImpl
import com.quietstack.voicetrade.data.repository.PortfolioRepositoryImpl
import com.quietstack.voicetrade.data.repository.SettingsRepositoryImpl
import com.quietstack.voicetrade.data.repository.VoiceSessionRepositoryImpl
import com.quietstack.voicetrade.data.repository.WatchlistRepositoryImpl
import com.quietstack.voicetrade.domain.repository.AuthRepository
import com.quietstack.voicetrade.domain.repository.HistoryRepository
import com.quietstack.voicetrade.domain.repository.MarketRepository
import com.quietstack.voicetrade.domain.repository.OrderRepository
import com.quietstack.voicetrade.domain.repository.PortfolioRepository
import com.quietstack.voicetrade.domain.repository.SessionServiceController
import com.quietstack.voicetrade.domain.repository.SettingsRepository
import com.quietstack.voicetrade.domain.repository.VoiceSessionRepository
import com.quietstack.voicetrade.domain.repository.WatchlistRepository
import com.quietstack.voicetrade.service.AndroidSessionServiceController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Placeholder host in the Retrofit base URL; [BackendInterceptor] swaps in the real one from BuildConfig. */
private const val PLACEHOLDER_HOST = "voicetrade.invalid"

class BackendInterceptor(private val store: SecureTokenStore) : Interceptor {
    private val base = BuildConfig.BACKEND_URL.toHttpUrlOrNull()

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (base != null && request.url.host == PLACEHOLDER_HOST) {
            val url = request.url.newBuilder()
                .scheme(base.scheme)
                .host(base.host)
                .port(base.port)
                .encodedPath(base.encodedPath.trimEnd('/') + request.url.encodedPath)
                .build()
            request = request.newBuilder().url(url).build()
        }
        val token = store.accessToken
        if (token != null && base != null && request.url.host == base.host) {
            request = request.newBuilder().header("Authorization", "Bearer $token").build()
        }
        val response = chain.proceed(request)
        // The server no longer knows this device token: sign out so the app returns to the sign-in screen.
        if (response.code == 401 && token != null && !request.url.encodedPath.contains("/auth/")) store.clear()
        return response
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun okHttp(store: SecureTokenStore): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BackendInterceptor(store))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        // Routers drop idle connections without telling anyone; a reused dead one costs ~13 s. Don't keep them around.
        .connectionPool(okhttp3.ConnectionPool(4, 15, TimeUnit.SECONDS))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                })
            }
        }
        .build()

    @Provides @Singleton
    fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://$PLACEHOLDER_HOST/")
        .client(client)
        .addConverterFactory(AppJson.instance.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun api(retrofit: Retrofit): VoiceTradeApi = retrofit.create(VoiceTradeApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "voicetrade.db")
            .fallbackToDestructiveMigration() // history is a local convenience copy; not worth a migration yet
            .build()

    @Provides fun sessionDao(db: AppDatabase): SessionDao = db.sessionDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun auth(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun market(impl: MarketRepositoryImpl): MarketRepository
    @Binds abstract fun portfolio(impl: PortfolioRepositoryImpl): PortfolioRepository
    @Binds abstract fun orders(impl: OrderRepositoryImpl): OrderRepository
    @Binds abstract fun watchlist(impl: WatchlistRepositoryImpl): WatchlistRepository
    @Binds abstract fun history(impl: HistoryRepositoryImpl): HistoryRepository
    @Binds abstract fun alerts(impl: com.quietstack.voicetrade.data.repository.AlertsRepositoryImpl): com.quietstack.voicetrade.domain.repository.AlertsRepository
    @Binds abstract fun research(impl: com.quietstack.voicetrade.data.repository.ResearchRepositoryImpl): com.quietstack.voicetrade.domain.repository.ResearchRepository
    @Binds abstract fun voiceSession(impl: VoiceSessionRepositoryImpl): VoiceSessionRepository
    @Binds abstract fun serviceController(impl: AndroidSessionServiceController): SessionServiceController
    @Binds abstract fun push(impl: com.quietstack.voicetrade.data.repository.PushRepositoryImpl): com.quietstack.voicetrade.domain.repository.PushRepository
}
