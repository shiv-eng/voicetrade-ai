package com.quietstack.voicetrade.domain.model

import java.math.BigDecimal
import java.time.Instant

data class Money(val amount: BigDecimal, val currency: String)

data class Instrument(
    val conid: Long,
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
)

data class Quote(
    val instrument: Instrument,
    val last: BigDecimal,
    val change: BigDecimal,
    val changePct: BigDecimal,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val dayHigh: BigDecimal?,
    val dayLow: BigDecimal?,
    val prevClose: BigDecimal?,
    val volume: Long?,
    val isDelayed: Boolean,
    val asOf: Instant,
    val week52High: BigDecimal? = null,
    val week52Low: BigDecimal? = null,
    val marketOpen: Boolean? = null,
)

data class BrokerStatus(val authenticated: Boolean, val accountId: String?, val paper: Boolean)
