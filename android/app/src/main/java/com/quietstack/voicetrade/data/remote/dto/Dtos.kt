@file:UseSerializers(BigDecimalSerializer::class)

package com.quietstack.voicetrade.data.remote.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/** Money is never a float: the server may send a JSON number or a string, both parse exactly. */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val element: JsonElement = (decoder as JsonDecoder).decodeJsonElement()
        return BigDecimal(element.jsonPrimitive.content)
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        (encoder as JsonEncoder).encodeJsonElement(JsonPrimitive(value))
    }
}


@Serializable
data class MoneyDto(val amount: BigDecimal, val currency: String)

@Serializable
data class InstrumentDto(
    val conid: Long,
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
)

@Serializable
data class QuoteDto(
    val instrument: InstrumentDto,
    val last: BigDecimal,
    val change: BigDecimal,
    val changePct: BigDecimal,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val dayHigh: BigDecimal? = null,
    val dayLow: BigDecimal? = null,
    val prevClose: BigDecimal? = null,
    val volume: Long? = null,
    val isDelayed: Boolean = false,
    val asOf: String,
    val week52High: BigDecimal? = null,
    val week52Low: BigDecimal? = null,
    val marketOpen: Boolean? = null,
)

@Serializable
data class PositionDto(
    val instrument: InstrumentDto,
    val quantity: BigDecimal,
    val avgCost: BigDecimal,
    val marketPrice: BigDecimal,
    val marketValue: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val dayChange: BigDecimal = BigDecimal.ZERO,
)

@Serializable
data class WalletDto(
    val currency: String,
    val cash: BigDecimal,
    val buyingPower: BigDecimal,
    val positionsValue: BigDecimal,
    val netLiquidation: BigDecimal,
)

@Serializable
data class AccountSummaryDto(
    val accountId: String,
    val isPaper: Boolean = true,
    val wallets: List<WalletDto>,
)

@Serializable
data class PnlLineDto(val currency: String, val daily: BigDecimal, val unrealized: BigDecimal, val realized: BigDecimal)

@Serializable
data class PnlDto(val items: List<PnlLineDto>)

@Serializable
data class WatchRowDto(val instrument: InstrumentDto, val quote: QuoteDto? = null)

@Serializable
data class OrderPreviewDto(
    val previewId: String,
    val instrument: InstrumentDto,
    val side: String,
    val quantity: Int,
    val type: String,
    val limitPrice: BigDecimal? = null,
    val estimatedValue: MoneyDto,
    val estimatedFees: MoneyDto? = null,
    val warnings: List<String> = emptyList(),
    val expiresAt: String,
    val kind: String = "PLACE",
)

/** state is one of Working, PartiallyFilled, Filled, Cancelled, Rejected. */
@Serializable
data class OrderStatusDto(
    val state: String,
    val filled: Int? = null,
    val avgPrice: BigDecimal? = null,
    val reason: String? = null,
)

@Serializable
data class OrderDto(
    val orderId: String,
    val instrument: InstrumentDto,
    val side: String,
    val quantity: Int,
    val type: String,
    val limitPrice: BigDecimal? = null,
    val status: OrderStatusDto,
    val updatedAt: String,
)

@Serializable
data class BrokerStatusDto(val authenticated: Boolean, val accountId: String? = null, val paper: Boolean = true)

// ---- requests / responses -------------------------------------------------

@Serializable
data class GoogleLoginRequest(val idToken: String)

@Serializable
data class ProfileDto(val userId: String, val email: String? = null, val name: String = "", val picture: String? = null)

@Serializable
data class AuthResponse(val token: String, val newAccount: Boolean = false, val profile: ProfileDto)

@Serializable
data class ResumeTurnDto(val role: String, val text: String)

@Serializable
data class StartSessionRequest(
    val language: String,
    val voice: String,
    val speechRate: Float,
    /** A past conversation to pick up: Mira is told about it and greets differently. */
    val resumeHistory: List<ResumeTurnDto>? = null,
)

@Serializable
data class AgoraDto(
    val appId: String,
    val channel: String,
    val token: String,
    val uid: Int,
    val tokenExpiresAt: String? = null,
)

@Serializable
data class SessionDto(
    val sessionId: String,
    val agora: AgoraDto,
    val wsUrl: String,
    val paper: Boolean = true,
)

@Serializable
data class TokenDto(val token: String, val tokenExpiresAt: String? = null)

@Serializable
data class TextRequest(val text: String)

@Serializable
data class ConfirmResponseDto(val orderId: String, val status: String)

@Serializable
data class RiskLimitsDto(
    val maxOrderValue: BigDecimal,
    val maxQty: Int,
    val maxOrdersPerDay: Int,
    val killSwitch: Boolean = false,
    val serverMax: ServerMaxDto? = null,
)

@Serializable
data class ServerMaxDto(val maxOrderValue: BigDecimal? = null, val maxQty: Int? = null, val maxOrdersPerDay: Int? = null)

@Serializable
data class KillSwitchDto(val on: Boolean)

@Serializable
data class ApiErrorDto(val code: String? = null, val message: String? = null)

// ---- action cards (WebSocket "card" events and local history) ------------

@Serializable
sealed interface CardDto {
    @Serializable @kotlinx.serialization.SerialName("quote")
    data class Quote(val quote: QuoteDto) : CardDto

    @Serializable @kotlinx.serialization.SerialName("positions")
    data class Positions(val positions: List<PositionDto>, val totals: List<MoneyDto>) : CardDto

    @Serializable @kotlinx.serialization.SerialName("account")
    data class Account(val summary: AccountSummaryDto) : CardDto

    @Serializable @kotlinx.serialization.SerialName("preview")
    data class Preview(val preview: OrderPreviewDto, val state: String = "ACTIVE") : CardDto

    @Serializable @kotlinx.serialization.SerialName("status")
    data class Status(val order: OrderDto) : CardDto

    @Serializable @kotlinx.serialization.SerialName("disambiguation")
    data class Disambiguation(val candidates: List<InstrumentDto>) : CardDto

    @Serializable @kotlinx.serialization.SerialName("error")
    data class Error(val message: String, val retryable: Boolean = false) : CardDto

    @Serializable @kotlinx.serialization.SerialName("chart")
    data class Chart(
        val instrument: InstrumentDto,
        val period: String,
        val points: List<List<Double>>,
        val first: Double,
        val last: Double,
        val high: Double,
        val low: Double,
        val changePct: Double,
        val currency: String,
    ) : CardDto

    @Serializable @kotlinx.serialization.SerialName("overview")
    data class Overview(
        val instrument: InstrumentDto,
        val quote: QuoteDto? = null,
        val rows: List<InfoRowDto> = emptyList(),
        val headlines: List<HeadlineDto> = emptyList(),
    ) : CardDto

    @Serializable @kotlinx.serialization.SerialName("ipos")
    data class Ipos(val market: String = "IN", val sections: List<IpoSectionDto> = emptyList()) : CardDto

    @Serializable @kotlinx.serialization.SerialName("ipo_detail")
    data class IpoDetail(
        val symbol: String,
        val series: String = "EQ",
        val name: String = "",
        val status: String = "",
        val type: String = "mainboard",
        @kotlinx.serialization.SerialName("price_low") val priceLow: Double? = null,
        @kotlinx.serialization.SerialName("price_high") val priceHigh: Double? = null,
        @kotlinx.serialization.SerialName("lot_size") val lotSize: Int? = null,
        @kotlinx.serialization.SerialName("min_investment") val minInvestment: Long? = null,
        @kotlinx.serialization.SerialName("issue_size") val issueSize: String? = null,
        val timeline: List<IpoStepDto> = emptyList(),
        val subscription: IpoSubscriptionDto = IpoSubscriptionDto(),
        val facts: List<InfoRowDto> = emptyList(),
        val links: List<IpoLinkDto> = emptyList(),
        val headlines: List<HeadlineDto> = emptyList(),
    ) : CardDto
}

@Serializable
data class IpoStepDto(val label: String, val date: String, val done: Boolean = false, val expected: Boolean = false)

@Serializable
data class IpoCategoryDto(val name: String, val times: Double = 0.0, val offered: Double? = null, val bid: Double? = null)

@Serializable
data class IpoSubscriptionDto(val overall: Double? = null, val categories: List<IpoCategoryDto> = emptyList())

@Serializable
data class IpoLinkDto(val label: String, val url: String)

@Serializable
data class IndexTileDto(val name: String, val symbol: String, val last: Double, val changePct: Double, val spark: List<Double> = emptyList())

@Serializable
data class MoverDto(
    val conid: Long, val symbol: String, val name: String, val currency: String = "INR", val last: BigDecimal, val changePct: Double,
)

@Serializable
data class MarketOverviewDto(
    val indices: List<IndexTileDto> = emptyList(), val gainers: List<MoverDto> = emptyList(), val losers: List<MoverDto> = emptyList(),
)

@Serializable
data class PortfolioHistoryDto(
    val currency: String, val points: List<List<Double>> = emptyList(), val first: Double = 0.0, val last: Double = 0.0, val changePct: Double = 0.0,
)

@Serializable
data class BriefingDto(val title: String = "", val text: String = "")

@Serializable
data class AlertDto(
    val id: Long, val conid: Long, val symbol: String, val name: String, val currency: String = "INR", val direction: String,
    val target: BigDecimal, val triggerPrice: BigDecimal? = null, val active: Boolean = true,
)

@Serializable
data class AddAlertRequest(val conid: Long, val target: String, val direction: String? = null)

@Serializable
data class AddAlertResponse(val id: Long)

@Serializable
data class AckRequest(val ids: List<Long>)

@Serializable
data class InfoRowDto(val label: String, val value: String)

@Serializable
data class HeadlineDto(val title: String, val publisher: String = "", val age: String = "", val url: String? = null)

@Serializable
data class IpoItemDto(
    val name: String, val tag: String = "", val detail: String = "", val price: String? = null, val extra: String? = null,
    val symbol: String = "", val series: String = "EQ", val status: String = "", val lot: String? = null, val minInvest: String? = null,
)

@Serializable
data class IpoSectionDto(val title: String, val items: List<IpoItemDto> = emptyList())

// ---- WebSocket envelope -----------------------------------------------------

@Serializable
data class WsEnvelope(
    val type: String,
    val sessionId: String? = null,
    val seq: Int = 0,
    val ts: String? = null,
    val data: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
data class TranscriptData(val role: String, val text: String, val isFinal: Boolean = true, val messageId: String)

@Serializable
data class AgentStateData(val state: String)

@Serializable
data class PlaybackData(val paused: Boolean = false, val resumable: Boolean = false)

@Serializable
data class CardData(val messageId: String, val card: CardDto)

@Serializable
data class PreviewCreatedData(val preview: OrderPreviewDto)

@Serializable
data class PreviewClosedData(val previewId: String, val reason: String)

@Serializable
data class OrderUpdateData(val order: OrderDto)

@Serializable
data class WatchlistData(val action: String, val instrument: InstrumentDto)

@Serializable
data class ErrorData(val code: String, val message: String, val retryable: Boolean = false)

