package com.quietstack.voicetrade.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.Wallet
import java.math.BigDecimal
import kotlin.math.abs

private val avatarPalette = listOf(
    Color(0xFF14B8A6), Color(0xFF6366F1), Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF3B82F6), Color(0xFF10B981),
)

/** Round monogram for a stock, tinted from its symbol so the same stock always looks the same. */
@Composable
fun SymbolAvatar(symbol: String, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val tint = avatarPalette[abs(symbol.hashCode()) % avatarPalette.size]
    Box(modifier.size(size).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol.take(2).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}

/** ▲ / ▼ with a signed percentage, so direction never depends on colour alone. */
@Composable
fun ChangePill(pct: BigDecimal, modifier: Modifier = Modifier) {
    val arrow = when {
        pct.signum() > 0 -> "▲ "
        pct.signum() < 0 -> "▼ "
        else -> ""
    }
    StatusChip(arrow + MoneyFormatter.formatPercent(pct).trimStart('+', '-'), pnlColor(pct), modifier)
}

/** Where today's price sits inside its 52-week range. */
@Composable
fun RangeBar(low: BigDecimal, high: BigDecimal, current: BigDecimal, currency: String, modifier: Modifier = Modifier) {
    val span = (high - low).takeIf { it.signum() > 0 }
    val fraction = if (span == null) 0.5f else ((current - low).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = 6.dp.toPx()
            val y = (size.height - h) / 2
            drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h))
            drawRoundRect(fill.copy(alpha = 0.35f), Offset(0f, y), Size(size.width * fraction, h), CornerRadius(h))
            drawCircle(fill, radius = 7.dp.toPx(), center = Offset(size.width * fraction, size.height / 2))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(MoneyFormatter.format(low, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f))
            Text(stringResource(R.string.week52), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f))
            Text(MoneyFormatter.format(high, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A practice-money wallet. Fixed dark gradients keep white text readable in light and dark themes. */
@Composable
fun WalletCard(wallet: Wallet, dayChange: BigDecimal?, modifier: Modifier = Modifier) {
    val brush = if (wallet.currency.equals("INR", true)) {
        Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF134E4A)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF4338CA), Color(0xFF312E81)))
    }
    val onCard = Color.White
    val soft = Color.White.copy(alpha = 0.78f)
    Box(modifier.background(brush, RoundedCornerShape(24.dp)).padding(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (wallet.currency.equals("INR", true)) R.string.wallet_inr else R.string.wallet_usd),
                    color = soft, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                )
                Text(wallet.currency, color = soft, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                MoneyFormatter.format(wallet.netLiquidation, wallet.currency, 0), color = onCard,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            )
            if (dayChange != null) {
                Text(
                    stringResource(R.string.today_move, MoneyFormatter.formatSigned(dayChange, wallet.currency, 0)),
                    color = soft, style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    Text(stringResource(R.string.cash), color = soft, style = MaterialTheme.typography.labelSmall)
                    Text(MoneyFormatter.format(wallet.cash, wallet.currency, 0), color = onCard, fontWeight = FontWeight.SemiBold)
                }
                Column {
                    Text(stringResource(R.string.holdings), color = soft, style = MaterialTheme.typography.labelSmall)
                    Text(MoneyFormatter.format(wallet.positionsValue, wallet.currency, 0), color = onCard, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun MarketStatePill(open: Boolean?, modifier: Modifier = Modifier) {
    if (open == null) return
    StatusChip(
        stringResource(if (open) R.string.market_open else R.string.market_closed),
        if (open) MaterialTheme.extra.gain else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier,
    )
}

