package com.quietstack.voicetrade.core.designsystem

import androidx.compose.foundation.Canvas
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.ChartData
import com.quietstack.voicetrade.domain.model.ChartPoint
import com.quietstack.voicetrade.domain.model.CompanyOverview
import com.quietstack.voicetrade.domain.model.Headline
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.IpoList
import com.quietstack.voicetrade.domain.model.IpoSection
import com.quietstack.voicetrade.domain.model.periodLabel
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v)

private fun pointTime(period: String, seconds: Long): String {
    val pattern = if (period == "1d" || period == "1w") "d MMM, h:mm a" else "d MMM yyyy"
    return DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))
}

/**
 * A price line with a soft fill. Drag a finger across it to read the price at that moment ([onScrub] gets the point,
 * or null when the finger lifts).
 */
@Composable
fun PriceChart(
    points: List<ChartPoint>,
    positive: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    onScrub: (ChartPoint?) -> Unit = {},
) {
    val color = if (positive) MaterialTheme.extra.gain else MaterialTheme.extra.loss
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(height))
        return
    }
    val lo = points.minOf { it.close }
    val hi = points.maxOf { it.close }
    val span = if (hi - lo > 0) hi - lo else 1.0
    var touchX by remember(points) { mutableStateOf<Float?>(null) }
    fun indexAt(x: Float, width: Float) = ((x / width) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)

    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(points) {
            detectHorizontalDragGestures(
                onDragStart = { o ->
                    touchX = o.x
                    onScrub(points[indexAt(o.x, size.width.toFloat())])
                },
                onHorizontalDrag = { change, _ ->
                    touchX = change.position.x
                    onScrub(points[indexAt(change.position.x, size.width.toFloat())])
                },
                onDragEnd = { touchX = null; onScrub(null) },
                onDragCancel = { touchX = null; onScrub(null) },
            )
        },
    ) {
        val pad = 8.dp.toPx()
        val w = size.width
        val h = size.height
        fun px(i: Int) = w * i / (points.size - 1)
        fun py(v: Double) = pad + (h - 2 * pad) * (1f - ((v - lo) / span).toFloat())

        val line = Path()
        val fill = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) {
                line.moveTo(px(0), py(p.close))
                fill.moveTo(px(0), py(p.close))
            } else {
                line.lineTo(px(i), py(p.close))
                fill.lineTo(px(i), py(p.close))
            }
        }
        fill.lineTo(w, h)
        fill.lineTo(0f, h)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val tx = touchX
        if (tx != null) {
            val i = indexAt(tx, w)
            val x = px(i)
            drawLine(color.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
            drawCircle(color, 5.dp.toPx(), Offset(x, py(points[i].close)))
        } else {
            drawCircle(color, 4.dp.toPx(), Offset(px(points.lastIndex), py(points.last().close)))
        }
    }
}

/** A chart in the conversation: price, move over the period, the line, and a way into the full stock page. */
@Composable
fun ChartCardView(chart: ChartData, modifier: Modifier = Modifier, onOpen: (Instrument) -> Unit = {}) {
    val i = chart.instrument
    var scrub by remember(chart) { mutableStateOf<ChartPoint?>(null) }
    Panel(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolAvatar(i.symbol)
                Column(Modifier.weight(1f)) {
                    Text(i.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        scrub?.let { pointTime(chart.period, it.timestamp) } ?: "${i.symbol} · ${periodLabel(chart.period)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        MoneyFormatter.format(bd(scrub?.close ?: chart.last), chart.currency),
                        style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold,
                    )
                    ChangeText(bd(chart.changePct))
                }
            }
            PriceChart(chart.points, positive = chart.changePct >= 0, height = 150.dp, onScrub = { scrub = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Low ${MoneyFormatter.format(bd(chart.low), chart.currency)}  ·  High ${MoneyFormatter.format(bd(chart.high), chart.currency)}",
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpen(i) }) { Text(tr("Details"), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
fun HeadlineRow(headline: Headline, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val base = modifier.fillMaxWidth()
    Column(if (onClick != null) base.clickable(onClick = onClick) else base, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(headline.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(headline.publisher, headline.age).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Label on the left, figure on the right, one per line. */
@Composable
fun KeyFigures(rows: List<com.quietstack.voicetrade.domain.model.InfoRow>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(r.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    r.value, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1.2f), maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Composable
fun OverviewCardView(overview: CompanyOverview, modifier: Modifier = Modifier, onOpen: (Instrument) -> Unit = {}) {
    val i = overview.instrument
    val q = overview.quote
    Panel(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolAvatar(i.symbol)
                Column(Modifier.weight(1f)) {
                    Text(i.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${i.symbol} · ${i.exchange}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (q != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(MoneyFormatter.format(q.last, i.currency), style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold)
                        ChangeText(q.changePct)
                    }
                }
            }
            if (overview.rows.isNotEmpty()) KeyFigures(overview.rows.take(8))
            if (overview.headlines.isNotEmpty()) {
                Hairline(Modifier.padding(start = 0.dp))
                Text(tr("In the news"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                overview.headlines.take(3).forEach { HeadlineRow(it) }
            }
            TextButton(onClick = { onOpen(i) }, modifier = Modifier.align(Alignment.End)) { Text(tr("Full details"), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun IpoSectionBlock(section: IpoSection, modifier: Modifier = Modifier, maxItems: Int = Int.MAX_VALUE) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(section.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        section.items.take(maxItems).forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.tag.isNotBlank()) {
                            Text(
                                item.tag, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                color = if (item.tag == "SME") MaterialTheme.extra.onPaper else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .background(if (item.tag == "SME") MaterialTheme.extra.paper else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item.price?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold) }
                    item.extra?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
fun IposCardView(ipos: IpoList, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null, onOpen: (com.quietstack.voicetrade.domain.model.IpoItem) -> Unit = {}) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (ipos.market == "US") tr("IPOs · United States") else tr("IPOs · India"),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        )
        if (ipos.sections.isEmpty()) Text(tr("No IPOs to show right now."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ipos.sections.take(2).forEach { section ->
            Text(tr(section.title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            section.items.take(3).forEach { item ->
                IpoCard(item, onClick = if (item.symbol.isNotBlank() && ipos.market != "US") ({ onOpen(item) }) else null)
            }
        }
        if (onSeeAll != null) TextButton(onClick = onSeeAll, modifier = Modifier.align(Alignment.End)) { Text(tr("See all IPOs"), fontWeight = FontWeight.SemiBold) }
    }
}
