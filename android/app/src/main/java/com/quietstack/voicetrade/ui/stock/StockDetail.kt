package com.quietstack.voicetrade.ui.stock

import com.quietstack.voicetrade.core.designsystem.AppButton
import com.quietstack.voicetrade.core.designsystem.AppOutlinedButton
import android.widget.Toast
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quietstack.voicetrade.core.designsystem.extra
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
import androidx.compose.ui.res.stringResource
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.PriceChart
import com.quietstack.voicetrade.core.designsystem.RangeBar
import com.quietstack.voicetrade.core.designsystem.SectionHeader
import com.quietstack.voicetrade.core.designsystem.SegmentedTabs
import com.quietstack.voicetrade.core.designsystem.TabularNumbers
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.ChartData
import com.quietstack.voicetrade.domain.model.ChartPeriods
import com.quietstack.voicetrade.domain.model.ChartPoint
import com.quietstack.voicetrade.domain.model.CompanyOverview
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.Side
import com.quietstack.voicetrade.domain.repository.OrderRepository
import com.quietstack.voicetrade.core.designsystem.OrderPreviewCard
import com.quietstack.voicetrade.ui.orders.rememberSecondsLeft
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

private const val CHART_FRESH_MS = 60_000L

data class StockDetailState(
    val overview: CompanyOverview? = null,
    val chart: ChartData? = null,
    val period: String = "1m",
    val chartLoading: Boolean = true,
    val error: AppError? = null,
    val watchlistMessage: String? = null,
    /** A hand-entered order: which side is open in the ticket, its preview once reviewed, and how it ended. */
    val orderSide: Side? = null,
    val orderPreview: OrderPreview? = null,
    val orderBusy: Boolean = false,
    val orderError: AppError? = null,
    val orderResult: String? = null,
)

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val research: ResearchRepository,
    private val alerts: com.quietstack.voicetrade.domain.repository.AlertsRepository,
    private val watchlist: UpdateWatchlistUseCase,
    private val orders: OrderRepository,
) : ViewModel() {
    private val conid = savedStateHandle.toRoute<StockDetail>().conid
    private val _state = MutableStateFlow(StockDetailState())
    val state: StateFlow<StockDetailState> = _state.asStateFlow()
    private var chartJob: Job? = null

    /** Charts already fetched for this stock, with when: switching back to a period shows at once instead of waiting again. */
    private val charts = mutableMapOf<String, Pair<Long, ChartData>>()

    private var prefetched = false

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

    private fun freshChart(period: String): ChartData? =
        charts[period]?.takeIf { System.currentTimeMillis() - it.first < CHART_FRESH_MS }?.second

    private fun loadChart(period: String) {
        chartJob?.cancel()
        freshChart(period)?.let { c -> _state.update { it.copy(chart = c, chartLoading = false) }; return }
        // Drop the old period's line right away: leaving it up while the new one loads looks like the tap did nothing.
        _state.update { it.copy(chart = null, chartLoading = true) }
        chartJob = viewModelScope.launch {
            research.chart(conid, period)
                .onSuccess { c ->
                    charts[period] = System.currentTimeMillis() to c
                    _state.update { it.copy(chart = c, chartLoading = false) }
                    prefetchOtherPeriods(period)
                }
                .onFailure { _state.update { it.copy(chart = null, chartLoading = false) } }
        }
    }

    /** Once one chart has loaded, fetch the other periods in the background so tapping 1W, 1M... is instant. */
    private fun prefetchOtherPeriods(loaded: String) {
        if (prefetched) return
        prefetched = true
        ChartPeriods.filter { it != loaded }.forEach { p ->
            viewModelScope.launch { research.chart(conid, p).onSuccess { c -> charts[p] = System.currentTimeMillis() to c } }
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

    fun openOrder(side: Side) = _state.update { it.copy(orderSide = side, orderPreview = null, orderError = null, orderResult = null) }

    fun closeOrder() {
        _state.value.orderPreview?.let { p -> viewModelScope.launch { orders.reject(p.previewId) } }
        _state.update { it.copy(orderSide = null, orderPreview = null, orderBusy = false, orderError = null, orderResult = null) }
    }

    /** Back from the review to the entry form, dropping the preview so it can't be confirmed by accident. */
    fun editOrder() {
        _state.value.orderPreview?.let { p -> viewModelScope.launch { orders.reject(p.previewId) } }
        _state.update { it.copy(orderPreview = null, orderError = null) }
    }

    fun reviewOrder(quantity: Int, limitPrice: String?) {
        val side = _state.value.orderSide ?: return
        _state.update { it.copy(orderBusy = true, orderError = null) }
        viewModelScope.launch {
            orders.previewOrder(conid, side, quantity, limitPrice)
                .onSuccess { p -> _state.update { it.copy(orderBusy = false, orderPreview = p) } }
                .onFailure { e -> _state.update { it.copy(orderBusy = false, orderError = e.asAppError()) } }
        }
    }

    fun confirmOrder() {
        val preview = _state.value.orderPreview ?: return
        _state.update { it.copy(orderBusy = true, orderError = null) }
        viewModelScope.launch {
            orders.confirm(preview.previewId)
                .onSuccess { r -> _state.update { it.copy(orderBusy = false, orderPreview = null, orderResult = r.status) } }
                .onFailure { e -> _state.update { it.copy(orderBusy = false, orderError = e.asAppError()) } }
        }
    }
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

    if (s.orderSide != null && ov != null) OrderTicketSheet(ov, s, viewModel)

    ScreenScaffold(title = ov?.instrument?.symbol.orEmpty(), onBack = onBack) { padding ->
        when {
            ov == null && s.error == null -> LoadingBox(Modifier.padding(padding))
            ov == null -> Column(Modifier.padding(padding).padding(16.dp)) {
                InlineError(messageText(context, s.error!!), { viewModel.load() })
            }
            else -> Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background), startY = 0f, endY = 120f))
                    .padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SmallActionChip(Icons.Filled.Visibility, tr("Watch"), Modifier.weight(1f), onClick = viewModel::addToWatchlist)
                    SmallActionChip(Icons.Filled.AddAlert, tr("Alert"), Modifier.weight(1f), onClick = { showAlert = true })
                    SmallActionChip(
                        Icons.Filled.Mic, tr("Ask Mira"), Modifier.weight(1.3f), tinted = true,
                        onClick = { onAsk("Tell me about ${ov.instrument.name}") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    AppOutlinedButton(
                        onClick = { viewModel.openOrder(Side.SELL) }, modifier = Modifier.weight(1f).height(54.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.extra.loss),
                        border = BorderStroke(1.dp, MaterialTheme.extra.loss.copy(alpha = 0.4f)),
                    ) { Text(tr("Sell"), fontWeight = FontWeight.Bold) }
                    AppButton(onClick = { viewModel.openOrder(Side.BUY) }, modifier = Modifier.weight(1f).height(54.dp)) {
                        Text(tr("Buy"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SmallActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, tinted: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.height(36.dp)
            .background(if (tinted) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .then(if (tinted) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null, tint = if (tinted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.height(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = if (tinted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Header(ov: CompanyOverview) {
    val i = ov.instrument
    val q = ov.quote
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(i.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(
                    "${i.symbol} · ${i.exchange}" + (q?.marketOpen?.let { " · " + stringResource(if (it) R.string.market_open else R.string.market_closed) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (q != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** Buy or sell typed in by hand: quantity (and a limit price if wanted), review, place. Mira is not involved. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OrderTicketSheet(ov: CompanyOverview, s: StockDetailState, viewModel: StockDetailViewModel) {
    val side = s.orderSide ?: return
    val context = LocalContext.current
    val cur = ov.instrument.currency
    val sideWord = if (side == Side.BUY) tr("Buy") else tr("Sell")
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = viewModel::closeOrder) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val preview = s.orderPreview
            when {
                s.orderResult != null -> {
                    Text(tr("Order placed"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${ov.instrument.symbol}: ${s.orderResult}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppButton(onClick = viewModel::closeOrder, modifier = Modifier.fillMaxWidth()) { Text(tr("Done")) }
                }
                preview != null -> {
                    val seconds = rememberSecondsLeft(preview)
                    OrderPreviewCard(
                        preview = preview, state = PreviewState.ACTIVE, secondsLeft = seconds, isSubmitting = s.orderBusy,
                        onConfirm = viewModel::confirmOrder, onCancel = viewModel::editOrder,
                    )
                }
                else -> {
                    var qty by remember { mutableStateOf("") }
                    var limit by remember { mutableStateOf("") }
                    var useLimit by remember { mutableStateOf(false) }
                    val last = ov.quote?.last
                    Text("$sideWord ${ov.instrument.symbol}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    last?.let { Text(tr("Price now") + " " + MoneyFormatter.format(it, cur), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    androidx.compose.material3.OutlinedTextField(
                        value = qty, onValueChange = { qty = it.filter(Char::isDigit).take(7) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("Quantity (shares)")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    val marketLabel = tr("Market")
                    val limitLabel = tr("Limit")
                    SegmentedTabs(
                        options = listOf(marketLabel, limitLabel), selected = if (useLimit) limitLabel else marketLabel,
                        onSelect = { useLimit = it == limitLabel }, modifier = Modifier.fillMaxWidth(), equalWidth = true,
                    )
                    if (useLimit) {
                        androidx.compose.material3.OutlinedTextField(
                            value = limit, onValueChange = { limit = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(tr("Limit price")) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        )
                    }
                    val shares = qty.toIntOrNull() ?: 0
                    val each = if (useLimit) limit.toBigDecimalOrNull() else last
                    if (shares > 0 && each != null) {
                        Text(tr("About") + " " + MoneyFormatter.format(each.multiply(BigDecimal(shares)), cur), fontWeight = FontWeight.SemiBold)
                    }
                    AppButton(
                        onClick = { viewModel.reviewOrder(shares, if (useLimit) limit else null) },
                        enabled = shares > 0 && (!useLimit || limit.toBigDecimalOrNull() != null) && !s.orderBusy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(tr("Review order"), fontWeight = FontWeight.Bold) }
                }
            }
            s.orderError?.let { Text(messageText(context, it), color = MaterialTheme.colorScheme.error) }
        }
    }
}
