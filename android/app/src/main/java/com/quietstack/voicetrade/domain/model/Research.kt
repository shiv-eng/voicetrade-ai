package com.quietstack.voicetrade.domain.model

/** One closing price on a chart. [timestamp] is Unix seconds. */
data class ChartPoint(val timestamp: Long, val close: Double)

data class ChartData(
    val instrument: Instrument,
    val period: String,
    val points: List<ChartPoint>,
    val first: Double,
    val last: Double,
    val high: Double,
    val low: Double,
    val changePct: Double,
    val currency: String,
)

data class InfoRow(val label: String, val value: String)

data class Headline(val title: String, val publisher: String, val age: String, val url: String? = null)

/** How a company is doing: key figures plus the latest headlines. */
data class CompanyOverview(
    val instrument: Instrument,
    val quote: Quote?,
    val rows: List<InfoRow>,
    val headlines: List<Headline>,
)

data class IpoItem(
    val name: String,
    val tag: String,
    val detail: String,
    val price: String?,
    val extra: String?,
    val symbol: String = "",
    val series: String = "EQ",
    val status: String = "",
    val lot: String? = null,
    val minInvest: String? = null,
)

data class IpoSection(val title: String, val items: List<IpoItem>)

data class IpoList(val market: String, val sections: List<IpoSection>)

val ChartPeriods = listOf("1d", "1w", "1m", "6m", "1y", "5y")

fun periodLabel(period: String): String = when (period) {
    "1d" -> "1D"
    "1w" -> "1W"
    "1m" -> "1M"
    "6m" -> "6M"
    "1y" -> "1Y"
    "5y" -> "5Y"
    else -> period.uppercase()
}

data class IpoStep(val label: String, val date: String, val done: Boolean, val expected: Boolean)

data class IpoCategory(val name: String, val times: Double, val offered: Double?, val bid: Double?)

data class IpoLink(val label: String, val url: String)

/** Everything the exchange publishes about one IPO, laid out for reading. */
data class IpoDetail(
    val symbol: String,
    val series: String,
    val name: String,
    val status: String,
    val isSme: Boolean,
    val priceLow: Double?,
    val priceHigh: Double?,
    val lotSize: Int?,
    val minInvestment: Long?,
    val issueSize: String?,
    val timeline: List<IpoStep>,
    val overallTimes: Double?,
    val categories: List<IpoCategory>,
    val facts: List<InfoRow>,
    val links: List<IpoLink>,
    val headlines: List<Headline>,
)

data class IndexTile(val name: String, val symbol: String, val last: Double, val changePct: Double, val spark: List<Double>)

data class Mover(
    val conid: Long,
    val symbol: String,
    val name: String,
    val currency: String,
    val last: java.math.BigDecimal,
    val changePct: Double,
)

data class MarketOverview(val indices: List<IndexTile>, val gainers: List<Mover>, val losers: List<Mover>)

data class PortfolioHistory(val currency: String, val points: List<ChartPoint>, val first: Double, val last: Double, val changePct: Double)

data class Briefing(val title: String, val text: String)

data class PriceAlert(
    val id: Long,
    val conid: Long,
    val symbol: String,
    val name: String,
    val currency: String,
    val direction: String,
    val target: java.math.BigDecimal,
    val triggerPrice: java.math.BigDecimal?,
    val active: Boolean,
)
