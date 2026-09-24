package com.quietstack.voicetrade.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.Instrument
import java.math.BigDecimal

/** Numbers line up in columns and don't jitter when a live price changes. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** A rounded surface that groups related rows. Rows inside are separated by hairlines, not by more cards. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val base = modifier.fillMaxWidth()
    Surface(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) { Column(content = content) }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
}

/** Title on the left, optional text action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.SemiBold) }
    }
}

/** "▲ 0.64%" in green or red. The arrow keeps direction readable without colour. */
@Composable
fun ChangeText(pct: BigDecimal, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall) {
    val arrow = when {
        pct.signum() > 0 -> "▲ "
        pct.signum() < 0 -> "▼ "
        else -> ""
    }
    Text(
        arrow + MoneyFormatter.formatPercent(pct).trimStart('+', '-'),
        modifier = modifier, color = pnlColor(pct), fontWeight = FontWeight.SemiBold,
        style = style.merge(TabularNumbers), maxLines = 1,
    )
}

/**
 * One stock on one line: monogram, name, symbol and exchange, then price with its move underneath.
 * [secondary] replaces the move (for holdings it is the profit or loss).
 */
@Composable
fun StockRow(
    instrument: Instrument,
    modifier: Modifier = Modifier,
    subtitle: String = "${instrument.symbol} · ${instrument.exchange}",
    price: String? = null,
    changePct: BigDecimal? = null,
    secondary: String? = null,
    secondaryColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val base = modifier.fillMaxWidth()
    Row(
        if (onClick != null) base.clickable(onClick = onClick) else base,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).padding(start = 16.dp, top = 13.dp, bottom = 13.dp, end = if (trailing == null) 16.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SymbolAvatar(instrument.symbol, size = 40.dp)
            Column(Modifier.weight(1f, fill = true), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    instrument.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = MaterialTheme.typography.titleSmall.fontSize * 1.15f,
                )
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (price != null) {
                    Text(price, style = MaterialTheme.typography.titleSmall.merge(TabularNumbers), fontWeight = FontWeight.SemiBold, maxLines = 1)
                } else {
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    secondary != null -> Text(
                        secondary, style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), fontWeight = FontWeight.SemiBold,
                        color = secondaryColor, maxLines = 1,
                    )
                    changePct != null -> ChangeText(changePct)
                }
            }
        }
        trailing?.invoke()
    }
}

/** Two or three options in one rounded track (INR | USD). */
@Composable
fun SegmentedTabs(
    options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, equalWidth: Boolean = false,
) {
    Row(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                (if (equalWidth) Modifier.weight(1f) else Modifier)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(50))
                    .clickable { onSelect(option) }
                    .padding(horizontal = if (equalWidth) 4.dp else 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/** Round initial for the signed-in person. */
@Composable
fun PersonAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    Box(
        modifier.size(size).background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)), CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (name?.trim()?.firstOrNull() ?: 'M').uppercase(), color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** A small labelled figure: "Cash" over "₹10,00,000". */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified, align: TextAlign = TextAlign.Start) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
        Text(
            value, style = MaterialTheme.typography.titleSmall.merge(TabularNumbers), fontWeight = FontWeight.SemiBold,
            color = valueColor, maxLines = 1, textAlign = align,
        )
    }
}
