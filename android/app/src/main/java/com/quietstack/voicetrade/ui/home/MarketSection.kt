package com.quietstack.voicetrade.ui.home

import androidx.compose.foundation.Canvas
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quietstack.voicetrade.core.designsystem.ChangeText
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.SectionHeader
import com.quietstack.voicetrade.core.designsystem.SegmentedTabs
import com.quietstack.voicetrade.core.designsystem.StockRow
import com.quietstack.voicetrade.core.designsystem.TabularNumbers
import com.quietstack.voicetrade.core.designsystem.extra
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.IndexTile
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.MarketOverview
import java.math.BigDecimal

/** A tiny trend line, no axes. */
@Composable
fun Sparkline(values: List<Double>, positive: Boolean, modifier: Modifier = Modifier) {
    val color = if (positive) MaterialTheme.extra.gain else MaterialTheme.extra.loss
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = if (hi - lo > 0) hi - lo else 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height * (1f - ((v - lo) / span).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun IndexTileCard(tile: IndexTile) {
    Column(
        Modifier.width(150.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(tile.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            "%,.0f".format(tile.last), style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold, maxLines = 1,
        )
        ChangeText(BigDecimal.valueOf(tile.changePct))
        Sparkline(tile.spark, tile.changePct >= 0, Modifier.fillMaxWidth().height(28.dp).padding(top = 6.dp))
    }
}

/** Nifty, Sensex, Nasdaq and S&P 500 at a glance. */
@Composable
fun IndexTiles(market: MarketOverview?) {
    val tiles = market?.indices.orEmpty()
    if (tiles.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(0.dp)) {
        items(tiles, key = { it.symbol }) { IndexTileCard(it) }
    }
}

/** Today's biggest risers and fallers among the Nifty 50. */
@Composable
fun TopMovers(market: MarketOverview?, onOpenStock: (Long) -> Unit) {
    if (market == null || (market.gainers.isEmpty() && market.losers.isEmpty())) return
    var gainersTab by remember { mutableStateOf(true) }
    val gainersLabel = tr("Gainers")
    val losersLabel = tr("Losers")
    val rows = if (gainersTab) market.gainers else market.losers
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SectionHeader(tr("Top movers"), Modifier.weight(1f))
            SegmentedTabs(listOf(gainersLabel, losersLabel), if (gainersTab) gainersLabel else losersLabel, { gainersTab = it == gainersLabel })
        }
        Panel {
            rows.forEachIndexed { i, m ->
                if (i > 0) Hairline()
                StockRow(
                    Instrument(m.conid, m.symbol, m.name, "NSE", m.currency),
                    price = MoneyFormatter.format(m.last, m.currency),
                    changePct = BigDecimal.valueOf(m.changePct),
                    onClick = { onOpenStock(m.conid) },
                )
            }
        }
    }
}
