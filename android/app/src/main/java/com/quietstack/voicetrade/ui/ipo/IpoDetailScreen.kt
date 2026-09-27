package com.quietstack.voicetrade.ui.ipo

import androidx.compose.foundation.clickable
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.HeadlineRow
import com.quietstack.voicetrade.core.designsystem.IpoTimeline
import com.quietstack.voicetrade.core.designsystem.KeyFigures
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.SectionHeader
import com.quietstack.voicetrade.core.designsystem.StatusBadge
import com.quietstack.voicetrade.core.designsystem.SubscriptionBars
import com.quietstack.voicetrade.domain.model.InfoRow
import com.quietstack.voicetrade.domain.model.IpoDetail
import com.quietstack.voicetrade.domain.repository.ResearchRepository
import com.quietstack.voicetrade.ui.common.InlineError
import com.quietstack.voicetrade.ui.common.LoadingBox
import com.quietstack.voicetrade.ui.common.ScreenScaffold
import com.quietstack.voicetrade.ui.common.messageText
import com.quietstack.voicetrade.ui.navigation.IpoDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IpoDetailState(val detail: IpoDetail? = null, val error: AppError? = null)

@HiltViewModel
class IpoDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val research: ResearchRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<IpoDetailRoute>()
    private val _state = MutableStateFlow(IpoDetailState())
    val state: StateFlow<IpoDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            research.ipoDetail(route.symbol, route.series, route.name)
                .onSuccess { d -> _state.update { it.copy(detail = d) } }
                .onFailure { e -> _state.update { it.copy(error = e.asAppError()) } }
        }
    }
}

@Composable
fun IpoDetailScreen(onBack: () -> Unit, viewModel: IpoDetailViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val d = s.detail
    ScreenScaffold(title = tr("IPO"), onBack = onBack) { padding ->
        when {
            d == null && s.error == null -> LoadingBox(Modifier.padding(padding))
            d == null -> Column(Modifier.padding(padding).padding(16.dp)) { InlineError(messageText(context, s.error!!), { viewModel.load() }) }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(d.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            StatusBadge(d.status)
                        }
                        Text(
                            (if (d.isSme) tr("Small and medium enterprise IPO") else tr("Mainboard IPO")) + " · ${d.symbol}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Panel {
                        KeyFigures(listOfNotNull(
                            if (d.priceLow != null && d.priceHigh != null) {
                                InfoRow(tr("Offer price"), if (d.priceLow == d.priceHigh) "₹%.0f".format(d.priceHigh) else "₹%.0f – ₹%.0f".format(d.priceLow, d.priceHigh))
                            } else null,
                            d.lotSize?.let { InfoRow(tr("Lot size"), "$it shares") },
                            d.minInvestment?.let { InfoRow(tr("Minimum investment"), "₹%,d".format(it)) },
                        ), Modifier.padding(16.dp))
                    }
                }
                if (d.timeline.isNotEmpty()) {
                    item { SectionHeader(tr("Timeline")) }
                    item { Panel { Column(Modifier.padding(16.dp)) { IpoTimeline(d.timeline) } } }
                }
                if (d.overallTimes != null || d.categories.isNotEmpty()) {
                    item { SectionHeader(tr("Subscription")) }
                    item { Panel { SubscriptionBars(d.overallTimes, d.categories, Modifier.padding(16.dp)) } }
                }
                if (!d.issueSize.isNullOrBlank()) {
                    item { SectionHeader(tr("About the issue")) }
                    item { Panel { ExpandableText(d.issueSize, Modifier.padding(16.dp)) } }
                }
                val (issueFacts, peopleFacts) = d.facts.partition { it.label in ISSUE_FACT_LABELS }
                if (issueFacts.isNotEmpty()) {
                    item { SectionHeader(tr("Issue details")) }
                    item { Panel { KeyFigures(issueFacts, Modifier.padding(16.dp)) } }
                }
                if (peopleFacts.isNotEmpty()) {
                    item { SectionHeader(tr("Managers and registrar")) }
                    item { Panel { KeyFigures(peopleFacts, Modifier.padding(16.dp)) } }
                }
                if (d.links.isNotEmpty()) {
                    item { SectionHeader(tr("Documents")) }
                    item {
                        Panel {
                            d.links.forEachIndexed { i, link ->
                                if (i > 0) Hairline(Modifier.padding(start = 16.dp))
                                Text(
                                    link.label + "  ›", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(link.url) } }.padding(16.dp),
                                )
                            }
                        }
                    }
                }
                if (d.headlines.isNotEmpty()) {
                    item { SectionHeader(tr("In the news")) }
                    item {
                        Panel {
                            d.headlines.forEachIndexed { i, h ->
                                if (i > 0) Hairline(Modifier.padding(start = 16.dp))
                                HeadlineRow(h, Modifier.padding(16.dp), onClick = h.url?.takeIf { it.isNotBlank() }?.let { url -> { runCatching { uri.openUri(url) } } })
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Information published by the exchange. Dates for allotment and listing are estimates. This is not a recommendation to apply. " +
                            "Grey market premium is unofficial, so it isn't shown.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val ISSUE_FACT_LABELS = setOf("Issue type", "Face value", "Bidding hours", "Retail limit")

/** A long paragraph shows three lines and opens up on request, so it doesn't crowd the facts around it. */
@Composable
private fun ExpandableText(text: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }) { Text(tr(if (expanded) "Show less" else "Show more"), fontWeight = FontWeight.SemiBold) }
    }
}
