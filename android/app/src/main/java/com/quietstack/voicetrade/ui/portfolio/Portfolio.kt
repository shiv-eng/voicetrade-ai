package com.quietstack.voicetrade.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.SectionHeader
import com.quietstack.voicetrade.core.designsystem.SegmentedTabs
import com.quietstack.voicetrade.core.designsystem.Stat
import com.quietstack.voicetrade.core.designsystem.StockRow
import com.quietstack.voicetrade.core.designsystem.TabularNumbers
import com.quietstack.voicetrade.core.designsystem.pnlColor
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.AccountSummary
import com.quietstack.voicetrade.domain.model.Pnl
import com.quietstack.voicetrade.domain.model.Position
import com.quietstack.voicetrade.domain.model.Wallet
import com.quietstack.voicetrade.domain.usecase.GetPortfolioUseCase
import com.quietstack.voicetrade.ui.common.EmptyState
import com.quietstack.voicetrade.ui.common.InlineError
import com.quietstack.voicetrade.ui.common.LoadingBox
import com.quietstack.voicetrade.ui.common.ScreenScaffold
import com.quietstack.voicetrade.ui.common.messageText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

enum class PortfolioSort { VALUE, PNL, SYMBOL }

data class PortfolioUiState(
    val summary: AccountSummary? = null,
    val positions: List<Position> = emptyList(),
    val pnl: Pnl? = null,
    val history: Map<String, com.quietstack.voicetrade.domain.model.PortfolioHistory> = emptyMap(),
    val sort: PortfolioSort = PortfolioSort.VALUE,
    val loading: Boolean = true,
    val error: AppError? = null,
)

sealed interface PortfolioEvent {
    data object Refresh : PortfolioEvent
    data class SortBy(val sort: PortfolioSort) : PortfolioEvent
}

@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val getPortfolio: GetPortfolioUseCase,
    private val research: com.quietstack.voicetrade.domain.repository.ResearchRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PortfolioUiState())
    val state: StateFlow<PortfolioUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onEvent(e: PortfolioEvent) {
        when (e) {
            PortfolioEvent.Refresh -> refresh()
            is PortfolioEvent.SortBy -> _state.update { it.copy(sort = e.sort, positions = sorted(it.positions, e.sort)) }
        }
    }

    private fun refresh() {
        _state.update { it.copy(error = null, loading = it.summary == null) }
        viewModelScope.launch {
            getPortfolio()
                .onSuccess { snap ->
                    _state.update {
                        it.copy(summary = snap.summary, pnl = snap.pnl, positions = sorted(snap.positions, it.sort), loading = false)
                    }
                }
                .onFailure { err -> _state.update { it.copy(loading = false, error = err.asAppError()) } }
            // the value history is nice to have: load it after the numbers are already on screen
            _state.value.summary?.wallets?.forEach { w ->
                research.portfolioHistory(w.currency, 90).onSuccess { h -> _state.update { it.copy(history = it.history + (w.currency to h)) } }
            }
        }
    }

    private fun sorted(list: List<Position>, sort: PortfolioSort) = when (sort) {
        PortfolioSort.VALUE -> list.sortedByDescending { it.marketValue }
        PortfolioSort.PNL -> list.sortedByDescending { it.unrealizedPnl }
        PortfolioSort.SYMBOL -> list.sortedBy { it.instrument.symbol }
    }
}

@Composable
fun PortfolioScreen(onBack: (() -> Unit)?, onOpenStock: (Long) -> Unit = {}, viewModel: PortfolioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(PortfolioEvent.Refresh)
        onPauseOrDispose { }
    }

    ScreenScaffold(
        title = stringResource(R.string.portfolio),
        onBack = onBack,
        actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(
                    PortfolioSort.VALUE to R.string.sort_value,
                    PortfolioSort.PNL to R.string.sort_pnl,
                    PortfolioSort.SYMBOL to R.string.sort_symbol,
                ).forEach { (sort, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { viewModel.onEvent(PortfolioEvent.SortBy(sort)); menu = false })
                }
            }
            IconButton(onClick = { viewModel.onEvent(PortfolioEvent.Refresh) }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        },
    ) { padding ->
        val summary = state.summary
        when {
            state.loading && summary == null -> LoadingBox(Modifier.padding(padding))
            state.error != null && summary == null -> Column(Modifier.padding(padding).padding(16.dp)) {
                InlineError(messageText(context, state.error!!), { viewModel.onEvent(PortfolioEvent.Refresh) })
            }
            summary != null -> {
                val wallets = summary.wallets
                // Start on the wallet that actually holds something.
                val currency = selected?.takeIf { c -> wallets.any { it.currency == c } }
                    ?: wallets.firstOrNull { w -> state.positions.any { it.instrument.currency == w.currency } }?.currency
                    ?: wallets.firstOrNull()?.currency
                val wallet = wallets.firstOrNull { it.currency == currency }
                val positions = state.positions.filter { it.instrument.currency == currency }
                val line = state.pnl?.items?.firstOrNull { it.currency == currency }

                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (wallets.size > 1) item {
                        SegmentedTabs(wallets.map { it.currency }, currency.orEmpty(), { selected = it })
                    }
                    if (wallet != null) item {
                        Summary(wallet, daily = line?.daily, unrealized = line?.unrealized ?: BigDecimal.ZERO, realized = line?.realized ?: BigDecimal.ZERO)
                    }
                    item { HistoryCard(state.history[currency]) }
                    if (wallet != null) item { AllocationCard(wallet, positions) }
                    item { SectionHeader(stringResource(R.string.holdings)) }
                    if (positions.isEmpty()) {
                        item { EmptyState(stringResource(R.string.no_positions), Modifier.height(200.dp)) }
                    } else item {
                        Panel {
                            positions.forEachIndexed { i, p ->
                                if (i > 0) Hairline()
                                val c = p.instrument.currency
                                val cost = p.avgCost.multiply(p.quantity)
                                val ret = if (cost.signum() > 0) p.unrealizedPnl.multiply(BigDecimal(100)).divide(cost, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
                                StockRow(
                                    p.instrument,
                                    subtitle = stringResource(R.string.qty_avg, p.quantity.stripTrailingZeros().toPlainString(), MoneyFormatter.format(p.avgCost, c)),
                                    price = MoneyFormatter.format(p.marketValue, c, 0),
                                    secondary = MoneyFormatter.formatSigned(p.unrealizedPnl, c, 0) + "  (" + MoneyFormatter.formatPercent(ret) + ")",
                                    secondaryColor = pnlColor(p.unrealizedPnl),
                                    onClick = { onOpenStock(p.instrument.conid) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One wallet: the big number first, then the day, then what it is made of. */
@Composable
private fun Summary(wallet: Wallet, daily: BigDecimal?, unrealized: BigDecimal, realized: BigDecimal) {
    val c = wallet.currency
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.total_value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                MoneyFormatter.format(wallet.netLiquidation, c, 0),
                style = MaterialTheme.typography.displaySmall.merge(TabularNumbers), fontWeight = FontWeight.Bold, maxLines = 1,
            )
            if (daily != null) {
                Text(
                    stringResource(R.string.today_move, MoneyFormatter.formatSigned(daily, c, 0)),
                    style = MaterialTheme.typography.titleSmall.merge(TabularNumbers), fontWeight = FontWeight.SemiBold, color = pnlColor(daily),
                )
            }
        }
        Panel {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Stat(stringResource(R.string.cash), MoneyFormatter.format(wallet.cash, c, 0))
                Stat(stringResource(R.string.invested), MoneyFormatter.format(wallet.positionsValue, c, 0))
                Stat(stringResource(R.string.unrealized), MoneyFormatter.formatSigned(unrealized, c, 0), valueColor = pnlColor(unrealized))
                Stat(stringResource(R.string.realized), MoneyFormatter.formatSigned(realized, c, 0), valueColor = pnlColor(realized))
            }
        }
    }
}


/** Account value since the first trade, rebuilt from your orders and daily closing prices. */
@Composable
private fun HistoryCard(history: com.quietstack.voicetrade.domain.model.PortfolioHistory?) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Account value"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (history == null) {
                Text(tr("Loading…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (history.points.size < 2) {
                Text(
                    tr("Your value history appears here after your first trade."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                var scrub by remember(history) { mutableStateOf<com.quietstack.voicetrade.domain.model.ChartPoint?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        MoneyFormatter.format(BigDecimal.valueOf(scrub?.close ?: history.last), history.currency, 0),
                        style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    com.quietstack.voicetrade.core.designsystem.ChangeText(BigDecimal.valueOf(history.changePct))
                }
                com.quietstack.voicetrade.core.designsystem.PriceChart(history.points, positive = history.changePct >= 0, height = 140.dp, onScrub = { scrub = it })
                Text(
                    tr("Since your first trade"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val allocationColors = listOf(
    androidx.compose.ui.graphics.Color(0xFF14B8A6), androidx.compose.ui.graphics.Color(0xFF6366F1), androidx.compose.ui.graphics.Color(0xFFF59E0B),
    androidx.compose.ui.graphics.Color(0xFFEC4899), androidx.compose.ui.graphics.Color(0xFF3B82F6), androidx.compose.ui.graphics.Color(0xFF10B981),
)

/** What the account is made of: cash and each holding, as a share of the total. */
@Composable
private fun AllocationCard(wallet: Wallet, positions: List<Position>) {
    val total = wallet.netLiquidation.toDouble()
    if (total <= 0) return
    data class Slice(val label: String, val value: Double, val color: androidx.compose.ui.graphics.Color)
    val held = positions.sortedByDescending { it.marketValue }
    val slices = buildList {
        held.take(5).forEachIndexed { i, p -> add(Slice(p.instrument.name, p.marketValue.toDouble(), allocationColors[i % allocationColors.size])) }
        val rest = held.drop(5).sumOf { it.marketValue.toDouble() }
        if (rest > 0) add(Slice(tr("Other holdings"), rest, androidx.compose.ui.graphics.Color(0xFF94A3B8)))
        add(Slice("Cash", wallet.cash.toDouble(), androidx.compose.ui.graphics.Color(0xFF64748B)))
    }.filter { it.value > 0 }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Allocation"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50))) {
                slices.forEach { s -> Box(Modifier.weight(s.value.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(s.color)) }
            }
            slices.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).background(s.color, CircleShape))
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("%.1f%%".format(s.value / total * 100), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
