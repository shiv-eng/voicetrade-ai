package com.quietstack.voicetrade.core.designsystem

import androidx.compose.foundation.background
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quietstack.voicetrade.domain.model.IpoCategory
import com.quietstack.voicetrade.domain.model.IpoDetail
import com.quietstack.voicetrade.domain.model.IpoItem
import com.quietstack.voicetrade.domain.model.IpoStep
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Live, Upcoming, Closed, Listed: a small coloured label. */
@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    if (status.isBlank()) return
    val color = when (status.lowercase()) {
        "live" -> MaterialTheme.extra.gain
        "upcoming" -> MaterialTheme.extra.orbAwaiting
        "listed", "priced" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (status.equals("live", true)) Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(tr(status), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun TypeTag(tag: String) {
    if (tag.isBlank()) return
    val sme = tag == "SME"
    Text(
        tag, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        color = if (sme) MaterialTheme.extra.onPaper else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(if (sme) MaterialTheme.extra.paper else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Label above value, both starting at the same edge. The value wraps rather than being cut off. */
@Composable
private fun LabelledValue(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.titleSmall.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
    }
}

/** Facts in two columns, as many rows as needed, with a thin line between the columns. Two columns hold
 * "111 shares" and "₹14,985" on a 360dp phone at a larger font size, where three columns cut them off. */
@Composable
fun StatGrid(vararg cells: Pair<String, String?>) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        cells.toList().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                LabelledValue(pair[0].first, pair[0].second, Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(line))
                if (pair.size > 1) LabelledValue(pair[1].first, pair[1].second, Modifier.weight(1f)) else Box(Modifier.weight(1f))
            }
        }
    }
}

/** One IPO as a card: who, what state it is in, when, at what price, how big a lot is and how popular it is. */
@Composable
fun IpoCard(item: IpoItem, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Panel(modifier, onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TypeTag(item.tag)
                }
                StatusBadge(item.status)
            }
            Hairline(Modifier.padding(start = 0.dp))
            StatGrid(
                *listOfNotNull(
                    tr("Offer date") to item.detail.ifBlank { null },
                    tr("Offer price") to item.price,
                    // US shares trade one at a time: no lot size, and "extra" is the total raise, not a subscription multiple.
                    if (item.series == "US") null else tr("Lot size") to item.lot,
                    if (item.lot != null || item.minInvest != null || item.extra != null) tr("Min. investment") to item.minInvest else null,
                    if (item.series == "US") tr("Raise size") to item.extra
                    else if (item.extra != null) tr("Subscribed") to item.extra.removeSuffix(" subscribed") else null,
                ).toTypedArray(),
            )
        }
    }
}

private fun shortDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}.getOrDefault(iso)

/** Opens, closes, allotment, listing: where the IPO is on its way. Dates marked expected are calculated, not announced. */
@Composable
fun IpoTimeline(steps: List<IpoStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { i, step ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == 0) Color.Transparent else if (step.done || steps[i - 1].done) primary else muted))
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (step.done) primary else muted))
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == steps.lastIndex) Color.Transparent else if (step.done) primary else muted))
                }
                Text(tr(step.label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    shortDate(step.date), style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                if (step.expected) Text(tr("expected"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** How many times each investor category has been covered. 1x means fully subscribed. */
@Composable
fun SubscriptionBars(overall: Double?, categories: List<IpoCategory>, modifier: Modifier = Modifier) {
    val gain = MaterialTheme.extra.gain
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        overall?.let { total ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Total subscription"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "${"%.2f".format(total)}x", style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold,
                    color = if (total >= 1) gain else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        categories.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${"%.2f".format(c.times)}x", style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(track)) {
                    val fraction = (c.times / 5.0).coerceIn(0.0, 1.0).toFloat()
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(50)).background(if (c.times >= 1) gain else MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** The IPO in a conversation: the essentials, and a way into the full page. */
@Composable
fun IpoDetailCardView(detail: IpoDetail, modifier: Modifier = Modifier, onOpen: (IpoDetail) -> Unit = {}) {
    Panel(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(detail.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TypeTag(if (detail.isSme) "SME" else tr("Mainboard"))
                }
                StatusBadge(detail.status)
            }
            val band = if (detail.priceLow != null && detail.priceHigh != null) {
                if (detail.priceLow == detail.priceHigh) "₹%.0f".format(detail.priceHigh) else "₹%.0f – ₹%.0f".format(detail.priceLow, detail.priceHigh)
            } else null
            StatGrid(
                tr("Offer price") to band, tr("Lot size") to detail.lotSize?.let { "$it shares" },
                tr("Min. investment") to detail.minInvestment?.let { "₹%,d".format(it) }
            )
            IpoTimeline(detail.timeline)
            detail.overallTimes?.let { SubscriptionBars(it, emptyList()) }
            TextButton(onClick = { onOpen(detail) }, modifier = Modifier.align(Alignment.End)) { Text(tr("Full details"), fontWeight = FontWeight.SemiBold) }
        }
    }
}
