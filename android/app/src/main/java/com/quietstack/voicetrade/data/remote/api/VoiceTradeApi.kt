package com.quietstack.voicetrade.data.remote.api

import com.quietstack.voicetrade.data.remote.dto.CardDto
import com.quietstack.voicetrade.data.remote.dto.AckRequest
import com.quietstack.voicetrade.data.remote.dto.DeviceTokenRequest
import com.quietstack.voicetrade.data.remote.dto.AddAlertResponse
import com.quietstack.voicetrade.data.remote.dto.AddAlertRequest
import com.quietstack.voicetrade.data.remote.dto.AlertDto
import com.quietstack.voicetrade.data.remote.dto.PortfolioHistoryDto
import com.quietstack.voicetrade.data.remote.dto.BriefingDto
import com.quietstack.voicetrade.data.remote.dto.MarketOverviewDto
import com.quietstack.voicetrade.data.remote.dto.AccountSummaryDto
import com.quietstack.voicetrade.data.remote.dto.ConfirmResponseDto
import com.quietstack.voicetrade.data.remote.dto.InstrumentDto
import com.quietstack.voicetrade.data.remote.dto.OrderDto
import com.quietstack.voicetrade.data.remote.dto.OrderPreviewDto
import com.quietstack.voicetrade.data.remote.dto.AuthResponse
import com.quietstack.voicetrade.data.remote.dto.GoogleLoginRequest
import com.quietstack.voicetrade.data.remote.dto.PnlDto
import com.quietstack.voicetrade.data.remote.dto.PositionDto
import com.quietstack.voicetrade.data.remote.dto.SessionDto
import com.quietstack.voicetrade.data.remote.dto.StartSessionRequest
import com.quietstack.voicetrade.data.remote.dto.TextRequest
import com.quietstack.voicetrade.data.remote.dto.TokenDto
import com.quietstack.voicetrade.data.remote.dto.WatchRowDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Backend contract from PRD section 9.6. Paths are relative to the linked backend URL. */
interface VoiceTradeApi {
    @POST("auth/google") suspend fun googleLogin(@Body body: GoogleLoginRequest): AuthResponse
    @POST("auth/guest") suspend fun guestLogin(@Body body: Map<String, String>): AuthResponse

    @POST("sessions") suspend fun startSession(@Body body: StartSessionRequest): SessionDto
    @DELETE("sessions/{id}") suspend fun endSession(@Path("id") id: String)
    @POST("sessions/{id}/text") suspend fun sendText(@Path("id") id: String, @Body body: TextRequest)
    @POST("sessions/{id}/token") suspend fun renewToken(@Path("id") id: String): TokenDto
    @POST("sessions/{id}/pause") suspend fun pauseSession(@Path("id") id: String)
    @POST("sessions/{id}/resume") suspend fun resumeSession(@Path("id") id: String)
    @POST("sessions/{id}/replay") suspend fun replaySession(@Path("id") id: String)

    @POST("previews/{id}/confirm") suspend fun confirmPreview(@Path("id") id: String): ConfirmResponseDto
    @POST("previews/{id}/reject") suspend fun rejectPreview(@Path("id") id: String)

    @GET("account") suspend fun account(): AccountSummaryDto
    @GET("positions") suspend fun positions(): List<PositionDto>
    @GET("pnl") suspend fun pnl(): PnlDto
    @GET("orders") suspend fun orders(@Query("status") status: String): List<OrderDto>
    @POST("orders/{id}/cancel-preview") suspend fun previewCancel(@Path("id") id: String): OrderPreviewDto

    @GET("search") suspend fun search(@Query("q") query: String): List<InstrumentDto>
    @GET("stocks/{id}/chart") suspend fun chart(@Path("id") id: Long, @Query("period") period: String): CardDto.Chart
    @GET("stocks/{id}/overview") suspend fun overview(@Path("id") id: Long): CardDto.Overview
    @GET("ipos") suspend fun ipos(@Query("market") market: String): CardDto.Ipos
    @GET("ipos/{symbol}") suspend fun ipoDetail(
        @Path("symbol") symbol: String, @Query("series") series: String, @Query("name") name: String?,
    ): CardDto.IpoDetail
    @GET("market/overview") suspend fun marketOverview(): MarketOverviewDto
    @GET("briefing") suspend fun briefing(@Query("lang") lang: String): BriefingDto
    @GET("portfolio/history") suspend fun portfolioHistory(@Query("currency") currency: String, @Query("days") days: Int): PortfolioHistoryDto
    @GET("alerts") suspend fun alerts(): List<AlertDto>
    @POST("alerts") suspend fun addAlert(@Body body: AddAlertRequest): AddAlertResponse
    @DELETE("alerts/{id}") suspend fun cancelAlert(@Path("id") id: Long)
    @GET("alerts/pending") suspend fun pendingAlerts(): List<AlertDto>
    @POST("alerts/ack") suspend fun ackAlerts(@Body body: AckRequest)
    @PUT("devices/token") suspend fun registerDevice(@Body body: DeviceTokenRequest)

    @GET("watchlist") suspend fun watchlist(): List<WatchRowDto>
    @PUT("watchlist/{conid}") suspend fun watchlistAdd(@Path("conid") conid: Long)
    @DELETE("watchlist/{conid}") suspend fun watchlistRemove(@Path("conid") conid: Long)
    @POST("watchlist/{conid}/move") suspend fun watchlistMove(@Path("conid") conid: Long, @Query("up") up: Boolean)

}
