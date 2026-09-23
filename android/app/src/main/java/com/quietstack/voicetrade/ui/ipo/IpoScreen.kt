package com.quietstack.voicetrade.ui.ipo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.items
import com.quietstack.voicetrade.core.designsystem.IpoCard
import com.quietstack.voicetrade.core.designsystem.extra
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.core.designsystem.SegmentedTabs
import com.quietstack.voicetrade.domain.model.IpoList
import com.quietstack.voicetrade.domain.repository.ResearchRepository
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
import javax.inject.Inject

data class IpoUiState(
    val market: String = "IN",
    val ipos: IpoList? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
)

@HiltViewModel
class IpoViewModel @Inject constructor(private val research: ResearchRepository) : ViewModel() {
    private val _state = MutableStateFlow(IpoUiState())
    val state: StateFlow<IpoUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun setMarket(market: String) {
        if (market == _state.value.market) return
        _state.update { it.copy(market = market, ipos = null) }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        val market = _state.value.market
        viewModelScope.launch {
            research.ipos(market)
                .onSuccess { list -> _state.update { if (it.market == market) it.copy(ipos = list, loading = false) else it } }
                .onFailure { e -> _state.update { if (it.market == market) it.copy(loading = false, error = e.asAppError()) else it } }
        }
    }
}

@Composable
fun IpoScreen(
    onBack: () -> Unit,
    onOpenIpo: (symbol: String, series: String, name: String?) -> Unit = { _, _, _ -> },
    viewModel: IpoViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenScaffold(
        title = tr("IPOs"), onBack = onBack,
        actions = { IconButton(onClick = viewModel::load) { Icon(Icons.Filled.Refresh, contentDescription = tr("Refresh")) } },
    ) { padding ->
        val indiaLabel = tr("India")
        val usLabel = tr("US")
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedTabs(
                options = listOf(indiaLabel, usLabel), selected = if (s.market == "US") usLabel else indiaLabel,
                onSelect = { viewModel.setMarket(if (it == usLabel) "US" else "IN") },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            val list = s.ipos
            when {
                s.loading && list == null -> LoadingBox()
                s.error != null && list == null -> Column(Modifier.padding(horizontal = 20.dp)) {
                    InlineError(messageText(context, s.error!!), { viewModel.load() })
                }
                list == null || list.sections.isEmpty() -> EmptyState(tr("No IPOs to show right now."))
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    list.sections.forEach { section ->
                        item(key = "h_" + section.title) {
                            val dot = when {
                                section.title.contains("open", true) -> MaterialTheme.extra.gain
                                section.title.contains("soon", true) -> MaterialTheme.extra.orbAwaiting
                                section.title.contains("listed", true) -> MaterialTheme.colorScheme.secondary
                                else -> null
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                if (dot != null) Box(Modifier.size(7.dp).background(dot, CircleShape))
                                Text(tr(section.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        items(section.items.size, key = { i -> section.title + section.items[i].symbol + section.items[i].name }) { i ->
                            val item = section.items[i]
                            IpoCard(item, onClick = if (item.symbol.isNotBlank() && list.market != "US") ({ onOpenIpo(item.symbol, item.series, item.name) }) else null)
                        }
                    }
                    item {
                        Text(
                            "Source: " + (if (list.market == "US") "Nasdaq" else "NSE India") + ". Information only, not a recommendation.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
