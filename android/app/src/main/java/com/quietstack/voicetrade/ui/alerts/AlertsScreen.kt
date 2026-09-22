package com.quietstack.voicetrade.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.SectionHeader
import com.quietstack.voicetrade.core.designsystem.TabularNumbers
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.PriceAlert
import com.quietstack.voicetrade.domain.repository.AlertsRepository
import com.quietstack.voicetrade.ui.common.EmptyState
import com.quietstack.voicetrade.ui.common.LoadingBox
import com.quietstack.voicetrade.ui.common.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertsState(val alerts: List<PriceAlert>? = null)

@HiltViewModel
class AlertsViewModel @Inject constructor(private val repo: AlertsRepository) : ViewModel() {
    private val _state = MutableStateFlow(AlertsState())
    val state: StateFlow<AlertsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { repo.list().onSuccess { list -> _state.update { it.copy(alerts = list) } }.onFailure { _state.update { s -> s.copy(alerts = s.alerts ?: emptyList()) } } }
    }

    fun cancel(alert: PriceAlert) {
        viewModelScope.launch { repo.cancel(alert.id); load() }
    }
}

@Composable
fun AlertsScreen(onBack: () -> Unit, viewModel: AlertsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val alerts = s.alerts
    ScreenScaffold(title = tr("Price alerts"), onBack = onBack) { padding ->
        when {
            alerts == null -> LoadingBox(Modifier.padding(padding))
            alerts.isEmpty() -> EmptyState("No alerts yet. Say \"tell me when Reliance crosses 1,300\", or tap the bell on any stock page.", Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val waiting = alerts.filter { it.active }
                val fired = alerts.filter { !it.active }
                if (waiting.isNotEmpty()) {
                    item { SectionHeader(tr("Waiting")) }
                    item { AlertList(waiting, onCancel = viewModel::cancel) }
                }
                if (fired.isNotEmpty()) {
                    item { SectionHeader(tr("Fired recently")) }
                    item { AlertList(fired, onCancel = null) }
                }
                item {
                    Text(
                        "You get a notification when a price is reached. Checked every few seconds on the server; the phone is told within about 15 minutes, " +
                            "or straight away if you are talking to Mira.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertList(alerts: List<PriceAlert>, onCancel: ((PriceAlert) -> Unit)?) {
    Panel {
        alerts.forEachIndexed { i, a ->
            if (i > 0) Hairline(Modifier.padding(start = 16.dp))
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        (if (a.direction == "above") "Above " else "Below ") + MoneyFormatter.format(a.target, a.currency, 2) +
                            (a.triggerPrice?.let { "  ·  hit at " + MoneyFormatter.format(it, a.currency, 2) } ?: ""),
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onCancel != null) IconButton(onClick = { onCancel(a) }) { Icon(Icons.Filled.Delete, contentDescription = tr("Remove alert")) }
            }
        }
    }
}
