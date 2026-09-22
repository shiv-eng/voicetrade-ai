package com.quietstack.voicetrade.ui.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.StockRow
import com.quietstack.voicetrade.core.designsystem.SymbolAvatar
import com.quietstack.voicetrade.core.designsystem.extra
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.Instrument
import com.quietstack.voicetrade.domain.model.WatchRow
import com.quietstack.voicetrade.domain.usecase.GetWatchlistUseCase
import com.quietstack.voicetrade.domain.usecase.SearchInstrumentUseCase
import com.quietstack.voicetrade.domain.usecase.UpdateWatchlistUseCase
import com.quietstack.voicetrade.ui.common.EmptyState
import com.quietstack.voicetrade.ui.common.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WatchlistUiState(
    val items: List<WatchRow> = emptyList(),
    val loaded: Boolean = false,
    val isPolling: Boolean = false,
    val searchOpen: Boolean = false,
    val query: String = "",
    val results: List<Instrument> = emptyList(),
    val full: Boolean = false,
)

sealed interface WatchlistEvent {
    data class Add(val instrument: Instrument) : WatchlistEvent
    data class Remove(val conid: Long) : WatchlistEvent
    data class Move(val conid: Long, val up: Boolean) : WatchlistEvent
    data class Search(val query: String) : WatchlistEvent
    data class SetSearchOpen(val open: Boolean) : WatchlistEvent
    data class SetVisible(val visible: Boolean) : WatchlistEvent
}

/** The watchlist lives on the server so Mira can read and edit it by voice; this screen just mirrors it. */
@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val getWatchlist: GetWatchlistUseCase,
    private val updateWatchlist: UpdateWatchlistUseCase,
    private val searchInstrument: SearchInstrumentUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state.asStateFlow()

    private val visible = MutableStateFlow(false)
    private var searchJob: Job? = null

    init {
        // Poll every 5 s, but only while the screen is on screen (PRD FR-34, battery NFR).
        viewModelScope.launch {
            visible.collectLatest { vis ->
                if (!vis) {
                    _state.update { it.copy(isPolling = false) }
                    return@collectLatest
                }
                _state.update { it.copy(isPolling = true) }
                while (true) {
                    reload()
                    delay(POLL_MS)
                }
            }
        }
    }

    fun onEvent(e: WatchlistEvent) {
        when (e) {
            is WatchlistEvent.Add -> viewModelScope.launch {
                updateWatchlist.add(e.instrument)
                    .onSuccess { _state.update { it.copy(searchOpen = false, query = "", results = emptyList(), full = false) } }
                    .onFailure { _state.update { it.copy(full = true) } }
                reload()
            }
            is WatchlistEvent.Remove -> viewModelScope.launch { updateWatchlist.remove(e.conid); reload() }
            is WatchlistEvent.Move -> viewModelScope.launch { updateWatchlist.move(e.conid, e.up); reload() }
            is WatchlistEvent.SetSearchOpen -> _state.update { it.copy(searchOpen = e.open, query = "", results = emptyList(), full = false) }
            is WatchlistEvent.SetVisible -> visible.value = e.visible
            is WatchlistEvent.Search -> {
                _state.update { it.copy(query = e.query) }
                searchJob?.cancel()
                searchJob = viewModelScope.launch {
                    delay(300)
                    searchInstrument(e.query).onSuccess { r -> _state.update { it.copy(results = r) } }
                }
            }
        }
    }

    private suspend fun reload() {
        getWatchlist().onSuccess { rows -> _state.update { it.copy(items = rows, loaded = true) } }
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}

@Composable
fun WatchlistScreen(onBack: (() -> Unit)?, onOpenStock: (Long) -> Unit = {}, viewModel: WatchlistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            viewModel.onEvent(WatchlistEvent.SetVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)))
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onEvent(WatchlistEvent.SetVisible(false))
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.watchlist),
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.onEvent(WatchlistEvent.SetSearchOpen(true)) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_to_watchlist))
            }
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            if (state.loaded) EmptyState(stringResource(R.string.watchlist_empty), Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(8.dp).background(
                            if (state.isPolling) MaterialTheme.extra.gain else MaterialTheme.colorScheme.outline, CircleShape,
                        ),
                    )
                    Text(
                        stringResource(if (state.isPolling) R.string.watchlist_live else R.string.watchlist_paused),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item {
                        Panel {
                            state.items.forEachIndexed { i, row ->
                                if (i > 0) Hairline()
                                WatchRowItem(
                                    row, onOpen = { onOpenStock(row.instrument.conid) },
                                    first = i == 0, last = i == state.items.lastIndex,
                                    onRemove = { viewModel.onEvent(WatchlistEvent.Remove(row.instrument.conid)) },
                                    onMove = { up -> viewModel.onEvent(WatchlistEvent.Move(row.instrument.conid, up)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.searchOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(WatchlistEvent.SetSearchOpen(false)) },
            title = { Text(stringResource(R.string.add_to_watchlist)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { viewModel.onEvent(WatchlistEvent.Search(it)) },
                        label = { Text(stringResource(R.string.search_company)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.full) Text(stringResource(R.string.watchlist_full), color = MaterialTheme.colorScheme.error)
                    state.results.take(6).forEach { inst ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.onEvent(WatchlistEvent.Add(inst)) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SymbolAvatar(inst.symbol, size = 36.dp)
                            Column {
                                Text(inst.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${inst.symbol} · ${inst.exchange}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onEvent(WatchlistEvent.SetSearchOpen(false)) }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun WatchRowItem(row: WatchRow, onOpen: () -> Unit, first: Boolean, last: Boolean, onRemove: () -> Unit, onMove: (up: Boolean) -> Unit) {
    val q = row.quote
    var menu by remember { mutableStateOf(false) }
    StockRow(
        row.instrument,
        price = q?.let { MoneyFormatter.format(it.last, row.instrument.currency) },
        changePct = q?.changePct,
        onClick = onOpen,
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!first) DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                        onClick = { menu = false; onMove(true) },
                    )
                    if (!last) DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                        onClick = { menu = false; onMove(false) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        },
    )
}
