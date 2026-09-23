package com.quietstack.voicetrade.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.core.util.SpeechNumberFormatter
import com.quietstack.voicetrade.domain.model.AccountSummary
import com.quietstack.voicetrade.domain.model.ActionCard
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.Order
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.OrderStatus
import com.quietstack.voicetrade.domain.model.OrderType
import com.quietstack.voicetrade.domain.model.Position
import com.quietstack.voicetrade.domain.model.PreviewKind
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.Quote
import com.quietstack.voicetrade.domain.model.Side
import java.math.BigDecimal

/** Always visible on session and order screens (FR-42). Text, not just colour. */
@Composable
fun PaperBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.extra.paper,
        contentColor = MaterialTheme.extra.onPaper,
    ) {
        Text(
            text = stringResource(R.string.paper_badge),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** +/- and colour: profit and loss are never signalled by colour alone. */
@Composable
fun pnlColor(value: BigDecimal): Color = when {
    value.signum() > 0 -> MaterialTheme.extra.gain
    value.signum() < 0 -> MaterialTheme.extra.loss
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun CardFrame(modifier: Modifier = Modifier, borderColor: Color? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, borderColor ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun QuoteCard(
    quote: Quote,
    modifier: Modifier = Modifier,
    onBuy: (Instrument) -> Unit = {},
    onSell: (Instrument) -> Unit = {},
    onAddToWatchlist: (Instrument) -> Unit = {},
) {
    val i = quote.instrument
    val speech = stringResource(
        R.string.quote_speech, i.name, SpeechNumberFormatter.spoken(quote.last, i.currency),
        SpeechNumberFormatter.spokenPercent(quote.changePct),
    )
    CardFrame(modifier.semantics(mergeDescendants = true) { contentDescription = speech }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SymbolAvatar(i.symbol)
            Column(Modifier.weight(1f)) {
                Text(i.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${i.symbol} · ${i.exchange}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MarketStatePill(quote.marketOpen)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(MoneyFormatter.format(quote.last, i.currency), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            ChangePill(quote.changePct, Modifier.padding(bottom = 6.dp))
        }
        Text(
            MoneyFormatter.formatSigned(quote.change, i.currency) + " " + stringResource(R.string.today),
            color = pnlColor(quote.change), fontWeight = FontWeight.SemiBold,
        )
        val low = quote.dayLow
        val high = quote.dayHigh
        if (low != null && high != null) {
            Text(
                stringResource(R.string.day_range, MoneyFormatter.format(low, i.currency), MoneyFormatter.format(high, i.currency)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val wLow = quote.week52Low
        val wHigh = quote.week52High
        if (wLow != null && wHigh != null) RangeBar(wLow, wHigh, quote.last, i.currency)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onBuy(i) }, contentPadding = PaddingValues(horizontal = 18.dp)) { Text(stringResource(R.string.buy)) }
            OutlinedButton(onClick = { onSell(i) }, contentPadding = PaddingValues(horizontal = 18.dp)) { Text(stringResource(R.string.sell)) }
            TextButton(onClick = { onAddToWatchlist(i) }) { Text(stringResource(R.string.add_watchlist)) }
        }
    }
}

@Composable
fun PositionsCard(
    positions: List<Position>,
    totals: List<com.quietstack.voicetrade.domain.model.Money>,
    modifier: Modifier = Modifier,
    onOpenPortfolio: () -> Unit = {},
) {
    CardFrame(modifier) {
        Text(stringResource(R.string.your_positions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        positions.take(5).forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolAvatar(p.instrument.symbol, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(p.instrument.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.qty_at, p.quantity.stripTrailingZeros().toPlainString(), MoneyFormatter.format(p.marketPrice, p.instrument.currency)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(MoneyFormatter.formatSigned(p.unrealizedPnl, p.instrument.currency, 0), color = pnlColor(p.unrealizedPnl), fontWeight = FontWeight.SemiBold)
            }
        }
        if (positions.size > 5) Text(stringResource(R.string.more_positions, positions.size - 5), style = MaterialTheme.typography.bodySmall)
        totals.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.total_pnl_in, t.currency), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(MoneyFormatter.formatSigned(t.amount, t.currency, 0), color = pnlColor(t.amount), fontWeight = FontWeight.Bold)
            }
        }
        TextButton(onClick = onOpenPortfolio) { Text(stringResource(R.string.open_portfolio)) }
    }
}

@Composable
fun AccountCard(summary: AccountSummary, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        summary.wallets.forEach { WalletCard(it, dayChange = null, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/**
 * The safety gate on screen: what will be sent, with a countdown, and two clearly separated buttons.
 * Confirm is only enabled while the preview is [PreviewState.ACTIVE].
 */
@Composable
fun OrderPreviewCard(
    preview: OrderPreview,
    state: PreviewState,
    secondsLeft: Int,
    modifier: Modifier = Modifier,
    isSubmitting: Boolean = false,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val i = preview.instrument
    val sideColor = if (preview.side == Side.BUY) MaterialTheme.extra.gain else MaterialTheme.extra.loss
    val sideWord = stringResource(if (preview.side == Side.BUY) R.string.side_buy else R.string.side_sell)
    val title = when (preview.kind) {
        PreviewKind.PLACE -> null
        PreviewKind.CANCEL -> stringResource(R.string.preview_cancel_title)
        PreviewKind.MODIFY -> stringResource(R.string.preview_modify_title)
    }
    val typeText = if (preview.type == OrderType.LIMIT && preview.limitPrice != null) {
        stringResource(R.string.limit_at, MoneyFormatter.format(preview.limitPrice, i.currency))
    } else {
        stringResource(R.string.market_order)
    }
    val summary = stringResource(
        R.string.preview_speech, sideWord, preview.quantity, i.name, i.exchange, typeText,
        SpeechNumberFormatter.spoken(preview.estimatedValue), secondsLeft,
    )
    val active = state == PreviewState.ACTIVE

    CardFrame(
        modifier.semantics(mergeDescendants = false) { contentDescription = summary },
        borderColor = if (active) MaterialTheme.extra.orbAwaiting.copy(alpha = 0.4f) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(sideWord, sideColor)
            PaperBadge()
            Spacer(Modifier.weight(1f))
            if (active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { (secondsLeft / 60f).coerceIn(0f, 1f) },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.extra.orbAwaiting,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${secondsLeft}s", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${preview.quantity} × ${i.name}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("${i.symbol} · ${i.exchange} · $typeText", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledValue(stringResource(R.string.estimated_value), MoneyFormatter.format(preview.estimatedValue))
        preview.estimatedFees?.let { LabeledValue(stringResource(R.string.estimated_fees), MoneyFormatter.format(it)) }
        preview.warnings.forEach {
            Text("⚠ $it", color = MaterialTheme.extra.onPaper, style = MaterialTheme.typography.bodyMedium)
        }
        if (active) {
            Spacer(Modifier.size(4.dp))
            // Confirm and Cancel are deliberately far apart to avoid mis-taps (PRD 8.4).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = onConfirm,
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    if (isSubmitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.confirm_order), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else {
            StatusChip(
                stringResource(
                    when (state) {
                        PreviewState.CONFIRMED -> R.string.preview_confirmed
                        PreviewState.REJECTED -> R.string.preview_rejected
                        PreviewState.EXPIRED -> R.string.preview_expired
                        else -> R.string.preview_replaced
                    },
                ),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun OrderStatusCard(order: Order, modifier: Modifier = Modifier, onCancel: (Order) -> Unit = {}) {
    val extra = MaterialTheme.extra
    val (label, color) = when (val s = order.status) {
        OrderStatus.Working -> stringResource(R.string.status_working) to extra.orbListening
        is OrderStatus.PartiallyFilled -> stringResource(R.string.status_partial, s.filled, order.quantity) to extra.orbListening
        is OrderStatus.Filled -> stringResource(R.string.status_filled) to extra.gain
        OrderStatus.Cancelled -> stringResource(R.string.status_cancelled) to MaterialTheme.colorScheme.onSurfaceVariant
        is OrderStatus.Rejected -> stringResource(R.string.status_rejected) to extra.loss
    }
    val i = order.instrument
    val side = stringResource(if (order.side == Side.BUY) R.string.side_buy else R.string.side_sell)
    CardFrame(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(label, color)
            PaperBadge()
        }
        Text("$side ${order.quantity} × ${i.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when (val s = order.status) {
            is OrderStatus.Filled -> Text(stringResource(R.string.filled_at, MoneyFormatter.format(s.avgPrice, i.currency)), fontWeight = FontWeight.SemiBold)
            is OrderStatus.PartiallyFilled -> {
                Text(stringResource(R.string.filled_at, MoneyFormatter.format(s.avgPrice, i.currency)))
                val fraction = if (order.quantity > 0) (s.filled.toFloat() / order.quantity.toFloat()).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = extra.orbListening, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh, strokeCap = StrokeCap.Round,
                )
            }
            is OrderStatus.Rejected -> Text(s.reason, color = extra.loss)
            else -> Unit
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.order_id, order.orderId), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (!order.status.isTerminal) {
                OutlinedButton(
                    onClick = { onCancel(order) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.loss),
                    border = BorderStroke(1.dp, extra.loss.copy(alpha = 0.45f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.cancel_order), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun DisambiguationCard(candidates: List<Instrument>, modifier: Modifier = Modifier, onPick: (Instrument) -> Unit = {}) {
    CardFrame(modifier) {
        Text(stringResource(R.string.which_one), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        candidates.take(4).forEach { c ->
            OutlinedButton(onClick = { onPick(c) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("${c.name} (${c.symbol}) · ${c.exchange}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ErrorCard(message: String, retryable: Boolean, modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            if (retryable) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** Read-only rendering of a stored or live card; the callbacks are inert in History. */
@Composable
fun ActionCardView(
    card: ActionCard,
    secondsLeft: Int = 0,
    isSubmitting: Boolean = false,
    actions: CardActions = CardActions.None,
    modifier: Modifier = Modifier,
) {
    when (card) {
        is ActionCard.QuoteCard -> QuoteCard(card.quote, modifier, actions.onBuy, actions.onSell, actions.onAddToWatchlist)
        is ActionCard.PositionsCard -> PositionsCard(card.positions, card.totals, modifier, actions.onOpenPortfolio)
        is ActionCard.AccountCard -> AccountCard(card.summary, modifier)
        is ActionCard.PreviewCard -> OrderPreviewCard(
            card.preview, card.state, secondsLeft, modifier, isSubmitting,
            onConfirm = { actions.onConfirm(card.preview) },
            onCancel = { actions.onReject(card.preview) },
        )
        is ActionCard.StatusCard -> OrderStatusCard(card.order, modifier, actions.onCancelOrder)
        is ActionCard.Disambiguation -> DisambiguationCard(card.candidates, modifier, actions.onPick)
        is ActionCard.ErrorCard -> ErrorCard(card.message, card.retryable && actions !== CardActions.None, modifier, actions.onRetry)
        is ActionCard.ChartCard -> ChartCardView(card.chart, modifier, actions.onOpenStock)
        is ActionCard.OverviewCard -> OverviewCardView(card.overview, modifier, actions.onOpenStock)
        is ActionCard.IposCard -> IposCardView(card.ipos, modifier, actions.onOpenIpos.takeIf { actions !== CardActions.None }, actions.onOpenIpo)
        is ActionCard.IpoDetailCard -> IpoDetailCardView(card.ipoDetail, modifier, actions.onOpenIpoDetail)
    }
}

data class CardActions(
    val onBuy: (Instrument) -> Unit = {},
    val onSell: (Instrument) -> Unit = {},
    val onAddToWatchlist: (Instrument) -> Unit = {},
    val onOpenPortfolio: () -> Unit = {},
    val onConfirm: (OrderPreview) -> Unit = {},
    val onReject: (OrderPreview) -> Unit = {},
    val onCancelOrder: (Order) -> Unit = {},
    val onPick: (Instrument) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenStock: (Instrument) -> Unit = {},
    val onOpenIpos: () -> Unit = {},
    val onOpenIpo: (com.quietstack.voicetrade.domain.model.IpoItem) -> Unit = {},
    val onOpenIpoDetail: (com.quietstack.voicetrade.domain.model.IpoDetail) -> Unit = {},
) {
    companion object {
        val None = CardActions()
    }
}
