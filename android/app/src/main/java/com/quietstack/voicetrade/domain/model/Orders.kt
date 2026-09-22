package com.quietstack.voicetrade.domain.model

import java.math.BigDecimal
import java.time.Instant

enum class Side { BUY, SELL }

enum class OrderType { MARKET, LIMIT }

enum class PreviewKind { PLACE, CANCEL, MODIFY }

/** Why a preview stopped being confirmable, or [ACTIVE] while it still is. */
enum class PreviewState { ACTIVE, CONFIRMED, REJECTED, EXPIRED, REPLACED }

data class OrderPreview(
    val previewId: String,
    val instrument: Instrument,
    val side: Side,
    val quantity: Int,
    val type: OrderType,
    val limitPrice: BigDecimal?,
    val estimatedValue: Money,
    val estimatedFees: Money?,
    val warnings: List<String>,
    val expiresAt: Instant,
    val kind: PreviewKind,
)

sealed interface OrderStatus {
    data object Working : OrderStatus
    data class PartiallyFilled(val filled: Int, val avgPrice: BigDecimal) : OrderStatus
    data class Filled(val avgPrice: BigDecimal) : OrderStatus
    data object Cancelled : OrderStatus
    data class Rejected(val reason: String) : OrderStatus

    val isTerminal: Boolean get() = this is Filled || this is Cancelled || this is Rejected
}

data class Order(
    val orderId: String,
    val instrument: Instrument,
    val side: Side,
    val quantity: Int,
    val type: OrderType,
    val limitPrice: BigDecimal?,
    val status: OrderStatus,
    val updatedAt: Instant,
)

data class ConfirmResult(val orderId: String, val status: String)

enum class OrderFilter(val apiValue: String) { OPEN("open"), FILLED("filled") }
