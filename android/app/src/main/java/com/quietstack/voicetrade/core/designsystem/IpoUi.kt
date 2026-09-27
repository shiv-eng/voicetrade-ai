package com.quietstack.voicetrade.core.designsystem

import androidx.compose.foundation.background
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import com.quietstack.voicetrade.domain.model.InfoRow
import com.quietstack.voicetrade.domain.model.IpoCategory
import com.quietstack.voicetrade.domain.model.IpoDetail
import com.quietstack.voicetrade.domain.model.IpoItem
import com.quietstack.voicetrade.domain.model.IpoStep
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Live, Upcoming, Closed, Listed: one coloured word, with a dot when it is open right now. */
@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    if (status.isBlank()) return
    val color = when (status.lowercase()) {
        "live" -> MaterialTheme.extra.gain
        "upcoming" -> MaterialTheme.extra.orbAwaiting
        "listed", "priced" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status.equals("live", true)) Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(tr(status), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

/** One IPO in a list. How much it shows depends on where it is: open ones show price, lot and demand; the rest stay short. */
@Composable
fun IpoCard(item: IpoItem, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val kind = if (item.tag == "SME") tr("Small and medium enterprise") else item.tag
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Panel(modifier, onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    val sub = when (item.status.lowercase()) {
                        "live" -> listOf(kind, item.detail)
                        "upcoming" -> listOf(item.detail.replaceFirstChar { it.uppercase() }, item.price)
                        "listed" -> listOf(item.price?.let { tr("Issue") + " " + it }, item.detail)
                        else -> listOf(item.detail.replaceFirstChar { it.uppercase() }, item.price)
                    }.filter { !it.isNullOrBlank() }.joinToString("  ·  ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (item.status.equals("listed", true) && item.now != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(item.now, style = MaterialTheme.typography.titleSmall.merge(TabularNumbers))
                        item.gain?.let { ChangeText(java.math.BigDecimal.valueOf(it)) }
                    }
                } else if (onClick != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = muted)
                }
            }
            if (item.status.equals("live", true)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoBox(tr("Price band"), item.price, Modifier.weight(1f))
                    InfoBox(tr("Lot size"), item.lot, Modifier.weight(1f))
                }
                item.sub?.let { times ->
                    val gain = MaterialTheme.extra.gain
                    val color = if (times >= 1) gain else MaterialTheme.colorScheme.primary
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text(tr("Subscribed"), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                            Text("%.1f×".format(times), style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), fontWeight = FontWeight.Bold, color = color)
                        }
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Box(Modifier.fillMaxWidth((times / 5.0).coerceIn(0.02, 1.0).toFloat()).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
                        }
                    }
                }
            }
        }
    }
}

/** A small label over a figure, on a slightly darker tile. */
@Composable
private fun InfoBox(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.titleSmall.merge(TabularNumbers), fontWeight = FontWeight.Bold)
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
                    "${"%.2f".format(total)} times", style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold,
                    color = if (total >= 1) gain else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        categories.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Text("${"%.2f".format(c.times)} times", style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
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
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(detail.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (detail.isSme) tr("Small and medium enterprise") else tr("Mainboard"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(detail.status)
            }
            val band = if (detail.priceLow != null && detail.priceHigh != null) {
                if (detail.priceLow == detail.priceHigh) "₹%.0f".format(detail.priceHigh) else "₹%.0f – ₹%.0f".format(detail.priceLow, detail.priceHigh)
            } else null
            KeyFigures(listOfNotNull(
                band?.let { InfoRow(tr("Offer price"), it) },
                detail.lotSize?.let { InfoRow(tr("Lot size"), "$it shares") },
                detail.minInvestment?.let { InfoRow(tr("Minimum investment"), "₹%,d".format(it)) },
            ))
            IpoTimeline(detail.timeline)
            detail.overallTimes?.let { SubscriptionBars(it, emptyList()) }
            TextButton(onClick = { onOpen(detail) }, modifier = Modifier.align(Alignment.End)) { Text(tr("Full details"), fontWeight = FontWeight.SemiBold) }
        }
    }
}
