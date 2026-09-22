package com.quietstack.voicetrade.data.mapper

import com.quietstack.voicetrade.data.remote.dto.AccountSummaryDto
import com.quietstack.voicetrade.data.remote.dto.BrokerStatusDto
import com.quietstack.voicetrade.data.remote.dto.CardDto
import com.quietstack.voicetrade.data.remote.dto.AlertDto
import com.quietstack.voicetrade.data.remote.dto.PortfolioHistoryDto
import com.quietstack.voicetrade.data.remote.dto.MarketOverviewDto
import com.quietstack.voicetrade.data.remote.dto.MoverDto
import com.quietstack.voicetrade.data.remote.dto.IndexTileDto
import com.quietstack.voicetrade.data.remote.dto.IpoLinkDto
import com.quietstack.voicetrade.data.remote.dto.IpoSubscriptionDto
import com.quietstack.voicetrade.data.remote.dto.IpoCategoryDto
import com.quietstack.voicetrade.data.remote.dto.IpoStepDto
import com.quietstack.voicetrade.data.remote.dto.IpoSectionDto
import com.quietstack.voicetrade.data.remote.dto.IpoItemDto
import com.quietstack.voicetrade.data.remote.dto.InfoRowDto
import com.quietstack.voicetrade.data.remote.dto.HeadlineDto
import com.quietstack.voicetrade.data.remote.dto.InstrumentDto
import com.quietstack.voicetrade.data.remote.dto.MoneyDto
import com.quietstack.voicetrade.data.remote.dto.OrderDto
import com.quietstack.voicetrade.data.remote.dto.OrderPreviewDto
import com.quietstack.voicetrade.data.remote.dto.OrderStatusDto
import com.quietstack.voicetrade.data.remote.dto.PnlDto
import com.quietstack.voicetrade.data.remote.dto.PnlLineDto
import com.quietstack.voicetrade.data.remote.dto.WalletDto
import com.quietstack.voicetrade.data.remote.dto.WatchRowDto
import com.quietstack.voicetrade.data.remote.dto.PositionDto
import com.quietstack.voicetrade.data.remote.dto.QuoteDto
import com.quietstack.voicetrade.data.remote.dto.RiskLimitsDto
import com.quietstack.voicetrade.data.remote.dto.ServerMaxDto
import com.quietstack.voicetrade.data.remote.dto.SessionDto
import com.quietstack.voicetrade.domain.model.AccountSummary
import com.quietstack.voicetrade.domain.model.ActionCard
import com.quietstack.voicetrade.domain.model.PriceAlert
import com.quietstack.voicetrade.domain.model.PortfolioHistory
import com.quietstack.voicetrade.domain.model.MarketOverview
import com.quietstack.voicetrade.domain.model.Mover
import com.quietstack.voicetrade.domain.model.IndexTile
import com.quietstack.voicetrade.domain.model.IpoDetail
import com.quietstack.voicetrade.domain.model.IpoLink
import com.quietstack.voicetrade.domain.model.IpoCategory
import com.quietstack.voicetrade.domain.model.IpoStep
import com.quietstack.voicetrade.domain.model.IpoSection
import com.quietstack.voicetrade.domain.model.IpoList
import com.quietstack.voicetrade.domain.model.IpoItem
import com.quietstack.voicetrade.domain.model.InfoRow
import com.quietstack.voicetrade.domain.model.Headline
import com.quietstack.voicetrade.domain.model.CompanyOverview
import com.quietstack.voicetrade.domain.model.ChartPoint
import com.quietstack.voicetrade.domain.model.ChartData
import com.quietstack.voicetrade.domain.model.BrokerStatus
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.Money
import com.quietstack.voicetrade.domain.model.Order
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.OrderStatus
import com.quietstack.voicetrade.domain.model.OrderType
import com.quietstack.voicetrade.domain.model.Pnl
import com.quietstack.voicetrade.domain.model.PnlLine
import com.quietstack.voicetrade.domain.model.Wallet
import com.quietstack.voicetrade.domain.model.WatchRow
import com.quietstack.voicetrade.domain.model.Position
import com.quietstack.voicetrade.domain.model.PreviewKind
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.Quote
import com.quietstack.voicetrade.domain.model.RiskLimits
import com.quietstack.voicetrade.domain.model.SessionInfo
import com.quietstack.voicetrade.domain.model.Side
import java.math.BigDecimal
import java.time.Instant

// ---- DTO -> domain --------------------------------------------------------

fun InstrumentDto.toDomain() = Instrument(conid, symbol, name, exchange, currency)

fun MoneyDto.toDomain() = Money(amount, currency)

fun QuoteDto.toDomain() = Quote(
    instrument = instrument.toDomain(),
    last = last,
    change = change,
    changePct = changePct,
    bid = bid,
    ask = ask,
    dayHigh = dayHigh,
    dayLow = dayLow,
    prevClose = prevClose,
    volume = volume,
    isDelayed = isDelayed,
    asOf = parseInstant(asOf),
    week52High = week52High,
    week52Low = week52Low,
    marketOpen = marketOpen,
)

fun PositionDto.toDomain() = Position(
    instrument = instrument.toDomain(),
    quantity = quantity,
    avgCost = avgCost,
    marketPrice = marketPrice,
    marketValue = marketValue,
    unrealizedPnl = unrealizedPnl,
    dayChange = dayChange,
)

fun WalletDto.toDomain() = Wallet(currency, cash, buyingPower, positionsValue, netLiquidation)

fun AccountSummaryDto.toDomain() = AccountSummary(accountId, isPaper, wallets.map { it.toDomain() })

fun PnlDto.toDomain() = Pnl(items.map { PnlLine(it.currency, it.daily, it.unrealized, it.realized) })

fun WatchRowDto.toDomain() = WatchRow(instrument.toDomain(), quote?.toDomain())

fun BrokerStatusDto.toDomain() = BrokerStatus(authenticated, accountId, paper)

fun OrderPreviewDto.toDomain() = OrderPreview(
    previewId = previewId,
    instrument = instrument.toDomain(),
    side = parseSide(side),
    quantity = quantity,
    type = parseType(type),
    limitPrice = limitPrice,
    estimatedValue = estimatedValue.toDomain(),
    estimatedFees = estimatedFees?.toDomain(),
    warnings = warnings,
    expiresAt = parseInstant(expiresAt),
    kind = runCatching { PreviewKind.valueOf(kind.uppercase()) }.getOrDefault(PreviewKind.PLACE),
)

fun OrderStatusDto.toDomain(): OrderStatus = when (state.lowercase()) {
    "filled" -> OrderStatus.Filled(avgPrice ?: BigDecimal.ZERO)
    "partiallyfilled", "partially_filled" -> OrderStatus.PartiallyFilled(filled ?: 0, avgPrice ?: BigDecimal.ZERO)
    "cancelled", "canceled" -> OrderStatus.Cancelled
    "rejected", "inactive" -> OrderStatus.Rejected(reason ?: "Rejected")
    else -> OrderStatus.Working
}

fun OrderDto.toDomain() = Order(
    orderId = orderId,
    instrument = instrument.toDomain(),
    side = parseSide(side),
    quantity = quantity,
    type = parseType(type),
    limitPrice = limitPrice,
    status = status.toDomain(),
    updatedAt = parseInstant(updatedAt),
)

fun RiskLimitsDto.toDomain() = RiskLimits(
    maxOrderValue = maxOrderValue,
    maxQuantity = maxQty,
    maxOrdersPerDay = maxOrdersPerDay,
    killSwitch = killSwitch,
    serverMaxOrderValue = serverMax?.maxOrderValue,
    serverMaxQuantity = serverMax?.maxQty,
    serverMaxOrdersPerDay = serverMax?.maxOrdersPerDay,
)

fun RiskLimits.toDto() = RiskLimitsDto(
    maxOrderValue = maxOrderValue,
    maxQty = maxQuantity,
    maxOrdersPerDay = maxOrdersPerDay,
    killSwitch = killSwitch,
    serverMax = ServerMaxDto(serverMaxOrderValue, serverMaxQuantity, serverMaxOrdersPerDay),
)

fun SessionDto.toDomain() = SessionInfo(
    sessionId = sessionId,
    appId = agora.appId,
    channel = agora.channel,
    token = agora.token,
    uid = agora.uid,
    wsUrl = wsUrl,
    paper = paper,
)

fun CardDto.toDomain(): ActionCard = when (this) {
    is CardDto.Quote -> ActionCard.QuoteCard(quote.toDomain())
    is CardDto.Positions -> ActionCard.PositionsCard(positions.map { it.toDomain() }, totals.map { it.toDomain() })
    is CardDto.Account -> ActionCard.AccountCard(summary.toDomain())
    is CardDto.Preview -> ActionCard.PreviewCard(
        preview.toDomain(),
        runCatching { PreviewState.valueOf(state.uppercase()) }.getOrDefault(PreviewState.ACTIVE),
    )
    is CardDto.Status -> ActionCard.StatusCard(order.toDomain())
    is CardDto.Disambiguation -> ActionCard.Disambiguation(candidates.map { it.toDomain() })
    is CardDto.Error -> ActionCard.ErrorCard(message, retryable)
    is CardDto.Chart -> ActionCard.ChartCard(toDomain())
    is CardDto.Overview -> ActionCard.OverviewCard(toDomain())
    is CardDto.Ipos -> ActionCard.IposCard(toDomain())
    is CardDto.IpoDetail -> ActionCard.IpoDetailCard(toDomain())
}

fun CardDto.Chart.toDomain() = ChartData(
    instrument = instrument.toDomain(), period = period,
    points = points.mapNotNull { if (it.size >= 2) ChartPoint(it[0].toLong(), it[1]) else null },
    first = first, last = last, high = high, low = low, changePct = changePct, currency = currency,
)

fun CardDto.Overview.toDomain() = CompanyOverview(
    instrument = instrument.toDomain(), quote = quote?.toDomain(),
    rows = rows.map { InfoRow(it.label, it.value) }, headlines = headlines.map { Headline(it.title, it.publisher, it.age, it.url) },
)

fun CardDto.Ipos.toDomain() = IpoList(
    market = market,
    sections = sections.map { s ->
        IpoSection(s.title, s.items.map { IpoItem(it.name, it.tag, it.detail, it.price, it.extra, it.symbol, it.series, it.status, it.lot, it.minInvest) })
    },
)

fun CardDto.IpoDetail.toDomain() = IpoDetail(
    symbol = symbol, series = series, name = name.ifBlank { symbol }, status = status, isSme = type.equals("SME", true),
    priceLow = priceLow, priceHigh = priceHigh, lotSize = lotSize, minInvestment = minInvestment, issueSize = issueSize,
    timeline = timeline.map { IpoStep(it.label, it.date, it.done, it.expected) },
    overallTimes = subscription.overall,
    categories = subscription.categories.map { IpoCategory(it.name, it.times, it.offered, it.bid) },
    facts = facts.map { InfoRow(it.label, it.value) }, links = links.map { IpoLink(it.label, it.url) },
    headlines = headlines.map { Headline(it.title, it.publisher, it.age, it.url) },
)

fun IndexTileDto.toDomain() = IndexTile(name, symbol, last, changePct, spark)

fun MoverDto.toDomain() = Mover(conid, symbol, name, currency, last, changePct)

fun MarketOverviewDto.toDomain() = MarketOverview(indices.map { it.toDomain() }, gainers.map { it.toDomain() }, losers.map { it.toDomain() })

fun PortfolioHistoryDto.toDomain() = PortfolioHistory(
    currency, points.mapNotNull { if (it.size >= 2) ChartPoint(it[0].toLong(), it[1]) else null }, first, last, changePct,
)

fun AlertDto.toDomain() = PriceAlert(id, conid, symbol, name, currency, direction, target, triggerPrice, active)

// ---- domain -> DTO (local history) -----------------------------------------

fun Instrument.toDto() = InstrumentDto(conid, symbol, name, exchange, currency)

fun Money.toDto() = MoneyDto(amount, currency)

fun Quote.toDto() = QuoteDto(
    instrument.toDto(), last, change, changePct, bid, ask, dayHigh, dayLow, prevClose, volume, isDelayed, asOf.toString(),
    week52High, week52Low, marketOpen,
)

fun Position.toDto() = PositionDto(instrument.toDto(), quantity, avgCost, marketPrice, marketValue, unrealizedPnl, dayChange)

fun AccountSummary.toDto() = AccountSummaryDto(
    accountId, isPaper, wallets.map { WalletDto(it.currency, it.cash, it.buyingPower, it.positionsValue, it.netLiquidation) },
)

fun OrderPreview.toDto() = OrderPreviewDto(
    previewId = previewId,
    instrument = instrument.toDto(),
    side = side.name,
    quantity = quantity,
    type = type.name,
    limitPrice = limitPrice,
    estimatedValue = estimatedValue.toDto(),
    estimatedFees = estimatedFees?.toDto(),
    warnings = warnings,
    expiresAt = expiresAt.toString(),
    kind = kind.name,
)

fun OrderStatus.toDto(): OrderStatusDto = when (this) {
    OrderStatus.Working -> OrderStatusDto("Working")
    is OrderStatus.PartiallyFilled -> OrderStatusDto("PartiallyFilled", filled = filled, avgPrice = avgPrice)
    is OrderStatus.Filled -> OrderStatusDto("Filled", avgPrice = avgPrice)
    OrderStatus.Cancelled -> OrderStatusDto("Cancelled")
    is OrderStatus.Rejected -> OrderStatusDto("Rejected", reason = reason)
}

fun Order.toDto() = OrderDto(
    orderId, instrument.toDto(), side.name, quantity, type.name, limitPrice, status.toDto(), updatedAt.toString(),
)

fun ActionCard.toDto(): CardDto = when (this) {
    is ActionCard.QuoteCard -> CardDto.Quote(quote.toDto())
    is ActionCard.PositionsCard -> CardDto.Positions(positions.map { it.toDto() }, totals.map { it.toDto() })
    is ActionCard.AccountCard -> CardDto.Account(summary.toDto())
    is ActionCard.PreviewCard -> CardDto.Preview(preview.toDto(), state.name)
    is ActionCard.StatusCard -> CardDto.Status(order.toDto())
    is ActionCard.Disambiguation -> CardDto.Disambiguation(candidates.map { it.toDto() })
    is ActionCard.ErrorCard -> CardDto.Error(message, retryable)
    is ActionCard.ChartCard -> CardDto.Chart(
        chart.instrument.toDto(), chart.period, chart.points.map { listOf(it.timestamp.toDouble(), it.close) },
        chart.first, chart.last, chart.high, chart.low, chart.changePct, chart.currency,
    )
    is ActionCard.OverviewCard -> CardDto.Overview(
        overview.instrument.toDto(), overview.quote?.toDto(), overview.rows.map { InfoRowDto(it.label, it.value) },
        overview.headlines.map { HeadlineDto(it.title, it.publisher, it.age, it.url) },
    )
    is ActionCard.IposCard -> CardDto.Ipos(
        ipos.market,
        ipos.sections.map { s ->
            IpoSectionDto(s.title, s.items.map { IpoItemDto(it.name, it.tag, it.detail, it.price, it.extra, it.symbol, it.series, it.status, it.lot, it.minInvest) })
        },
    )
    is ActionCard.IpoDetailCard -> CardDto.IpoDetail(
        symbol = ipoDetail.symbol, series = ipoDetail.series, name = ipoDetail.name, status = ipoDetail.status,
        type = if (ipoDetail.isSme) "SME" else "mainboard", priceLow = ipoDetail.priceLow, priceHigh = ipoDetail.priceHigh,
        lotSize = ipoDetail.lotSize, minInvestment = ipoDetail.minInvestment, issueSize = ipoDetail.issueSize,
        timeline = ipoDetail.timeline.map { IpoStepDto(it.label, it.date, it.done, it.expected) },
        subscription = IpoSubscriptionDto(ipoDetail.overallTimes, ipoDetail.categories.map { IpoCategoryDto(it.name, it.times, it.offered, it.bid) }),
        facts = ipoDetail.facts.map { InfoRowDto(it.label, it.value) }, links = ipoDetail.links.map { IpoLinkDto(it.label, it.url) },
        headlines = ipoDetail.headlines.map { HeadlineDto(it.title, it.publisher, it.age, it.url) },
    )
}

// ---- helpers ---------------------------------------------------------------

fun parseSide(raw: String): Side = if (raw.equals("SELL", ignoreCase = true) || raw.equals("S", true)) Side.SELL else Side.BUY

fun parseType(raw: String): OrderType =
    if (raw.equals("LMT", ignoreCase = true) || raw.equals("LIMIT", ignoreCase = true)) OrderType.LIMIT else OrderType.MARKET

fun parseInstant(raw: String?): Instant = runCatching { Instant.parse(raw) }.getOrElse { Instant.now() }
