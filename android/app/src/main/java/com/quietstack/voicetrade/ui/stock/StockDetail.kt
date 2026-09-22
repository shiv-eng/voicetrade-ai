package com.quietstack.voicetrade.ui.stock

import android.widget.Toast
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.core.designsystem.ChangeText
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.HeadlineRow
import com.quietstack.voicetrade.core.designsystem.KeyFigures
import com.quietstack.voicetrade.core.designsystem.MarketStatePill
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.PriceChart
import com.quietstack.voicetrade.core.designsystem.RangeBar
import com.quietstack.voicetrade.core.designsystem.SectionHeader
import com.quietstack.voicetrade.core.designsystem.SegmentedTabs
import com.quietstack.voicetrade.core.designsystem.SymbolAvatar
import com.quietstack.voicetrade.core.designsystem.TabularNumbers
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.ChartData
import com.quietstack.voicetrade.domain.model.ChartPeriods
import com.quietstack.voicetrade.domain.model.ChartPoint
import com.quietstack.voicetrade.domain.model.CompanyOverview
import com.quietstack.voicetrade.domain.model.periodLabel
import com.quietstack.voicetrade.domain.repository.ResearchRepository
import com.quietstack.voicetrade.domain.usecase.UpdateWatchlistUseCase
import com.quietstack.voicetrade.ui.common.InlineError
import com.quietstack.voicetrade.ui.common.LoadingBox
import com.quietstack.voicetrade.ui.common.ScreenScaffold
import com.quietstack.voicetrade.ui.common.messageText
import com.quietstack.voicetrade.ui.navigation.StockDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class StockDetailState(
    val overview: CompanyOverview? = null,
    val chart: ChartData? = null,
    val period: String = "1m",
    val chartLoading: Boolean = true,
    val error: AppError? = null,
    val watchlistMessage: String? = null,
)

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val research: ResearchRepository,
    private val alerts: com.quietstack.voicetrade.domain.repository.AlertsRepository,
    private val watchlist: UpdateWatchlistUseCase,
) : ViewModel() {
    private val conid = savedStateHandle.toRoute<StockDetail>().conid
    private val _state = MutableStateFlow(StockDetailState())
    val state: StateFlow<StockDetailState> = _state.asStateFlow()
    private var chartJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            research.overview(conid)
                .onSuccess { ov -> _state.update { it.copy(overview = ov) } }
                .onFailure { e -> _state.update { it.copy(error = e.asAppError()) } }
        }
        loadChart(_state.value.period)
    }

    fun setPeriod(period: String) {
        if (period == _state.value.period && _state.value.chart != null) return
        _state.update { it.copy(period = period) }
        loadChart(period)
    }

    private fun loadChart(period: String) {
        chartJob?.cancel()
        _state.update { it.copy(chartLoading = true) }
        chartJob = viewModelScope.launch {
            research.chart(conid, period)
                .onSuccess { c -> _state.update { it.copy(chart = c, chartLoading = false) } }
                .onFailure { _state.update { it.copy(chart = null, chartLoading = false) } }
        }
    }

    fun addToWatchlist() {
        val inst = _state.value.overview?.instrument ?: return
        viewModelScope.launch {
            watchlist.add(inst)
                .onSuccess { _state.update { it.copy(watchlistMessage = "Added ${inst.symbol} to your watchlist") } }
                .onFailure { e -> _state.update { it.copy(watchlistMessage = e.asAppError().let { _ -> "Couldn't add to watchlist" }) } }
        }
    }

    fun addAlert(target: String) {
        val inst = _state.value.overview?.instrument ?: return
        viewModelScope.launch {
            alerts.add(inst.conid, target, null)
                .onSuccess { _state.update { it.copy(watchlistMessage = "Alert set: I'll tell you when ${inst.symbol} reaches $target") } }
                .onFailure { _state.update { it.copy(watchlistMessage = "Couldn't set that alert") } }
        }
    }

    fun messageShown() = _state.update { it.copy(watchlistMessage = null) }
}

@Composable
fun StockDetailScreen(
    onBack: () -> Unit,
    onAsk: (String) -> Unit,
    viewModel: StockDetailViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val ov = s.overview
    var showAlert by remember { mutableStateOf(false) }

    s.watchlistMessage?.let { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        viewModel.messageShown()
    }

    if (showAlert && ov != null) {
        var price by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(tr("Price alert")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notify me when ${ov.instrument.symbol} reaches this price. Now: ${ov.quote?.let { MoneyFormatter.format(it.last, ov.instrument.currency) } ?: "—"}")
                    androidx.compose.material3.OutlinedTextField(
                        value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                        label = { Text(tr("Price")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { if (price.isNotBlank()) viewModel.addAlert(price); showAlert = false }) { Text(tr("Set alert")) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showAlert = false }) { Text(tr("Cancel")) } },
        )
    }

    ScreenScaffold(title = ov?.instrument?.symbol.orEmpty(), onBack = onBack) { padding ->
        when {
            ov == null && s.error == null -> LoadingBox(Modifier.padding(padding))
            ov == null -> Column(Modifier.padding(padding).padding(16.dp)) {
                InlineError(messageText(context, s.error!!), { viewModel.load() })
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item { Header(ov) }
                item {
                    SegmentedTabs(
                        options = ChartPeriods.map(::periodLabel), selected = periodLabel(s.period),
                        onSelect = { label -> viewModel.setPeriod(ChartPeriods.first { periodLabel(it) == label }) },
                        modifier = Modifier.fillMaxWidth(), equalWidth = true,
                    )
                }
                item { ChartBlock(s.chart, s.chartLoading) }
                ov.quote?.let { q ->
                    val lo = q.week52Low
                    val hi = q.week52High
                    if (lo != null && hi != null) item { Panel { Box(Modifier.padding(16.dp)) { RangeBar(lo, hi, q.last, ov.instrument.currency) } } }
                }
                if (ov.rows.isNotEmpty()) {
                    item { SectionHeader(tr("About the company")) }
                    item { Panel { KeyFigures(ov.rows, Modifier.padding(16.dp)) } }
                }
                if (ov.headlines.isNotEmpty()) {
                    item { SectionHeader(tr("In the news")) }
                    item {
                        Panel {
                            ov.headlines.forEachIndexed { i, h ->
                                if (i > 0) Hairline(Modifier.padding(start = 16.dp))
                                HeadlineRow(h, Modifier.padding(16.dp), onClick = h.url?.takeIf { it.isNotBlank() }?.let { url -> { runCatching { uri.openUri(url) } } })
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onAsk("Buy ${ov.instrument.name}") }, modifier = Modifier.weight(1f)) { Text(tr("Buy")) }
                            FilledTonalButton(onClick = { onAsk("Sell ${ov.instrument.name}") }, modifier = Modifier.weight(1f)) { Text(tr("Sell")) }
                            OutlinedButton(onClick = viewModel::addToWatchlist, modifier = Modifier.weight(1f)) { Text(tr("Watch")) }
                        }
                        OutlinedButton(onClick = { showAlert = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("Set a price alert")) }
                        OutlinedButton(onClick = { onAsk("Tell me about ${ov.instrument.name}") }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Ask Mira about this stock"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(ov: CompanyOverview) {
    val i = ov.instrument
    val q = ov.quote
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SymbolAvatar(i.symbol, size = 52.dp)
            Column(Modifier.weight(1f)) {
                Text(i.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${i.symbol} · ${i.exchange}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MarketStatePill(q?.marketOpen)
        }
        if (q != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    MoneyFormatter.format(q.last, i.currency),
                    style = MaterialTheme.typography.displaySmall.merge(TabularNumbers), fontWeight = FontWeight.Bold,
                )
                ChangeText(q.changePct, Modifier.padding(bottom = 8.dp), MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun ChartBlock(chart: ChartData?, loading: Boolean) {
    var scrub by remember(chart) { mutableStateOf<ChartPoint?>(null) }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                chart == null && loading -> Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                chart == null -> Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    Text(tr("Chart isn't available right now."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    val cur = chart.currency
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            MoneyFormatter.format(BigDecimal.valueOf(scrub?.close ?: chart.last), cur),
                            style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        ChangeText(BigDecimal.valueOf(chart.changePct))
                    }
                    PriceChart(chart.points, positive = chart.changePct >= 0, height = 220.dp, onScrub = { scrub = it })
                    Text(
                        "Low ${MoneyFormatter.format(BigDecimal.valueOf(chart.low), cur)}  ·  High ${MoneyFormatter.format(BigDecimal.valueOf(chart.high), cur)}",
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
