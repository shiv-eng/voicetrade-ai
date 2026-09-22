package com.quietstack.voicetrade.domain.model

import java.math.BigDecimal

data class Position(
    val instrument: Instrument,
    val quantity: BigDecimal,
    val avgCost: BigDecimal,
    val marketPrice: BigDecimal,
    val marketValue: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val dayChange: BigDecimal = BigDecimal.ZERO,
)

/** One practice-money wallet. Every user has a rupee wallet and a dollar wallet. */
data class Wallet(
    val currency: String,
    val cash: BigDecimal,
    val buyingPower: BigDecimal,
    val positionsValue: BigDecimal,
    val netLiquidation: BigDecimal,
)

data class AccountSummary(
    val accountId: String,
    val isPaper: Boolean,
    val wallets: List<Wallet>,
) {
    fun wallet(currency: String): Wallet? = wallets.firstOrNull { it.currency.equals(currency, ignoreCase = true) }
}

data class PnlLine(val currency: String, val daily: BigDecimal, val unrealized: BigDecimal, val realized: BigDecimal)

data class Pnl(val items: List<PnlLine>)
