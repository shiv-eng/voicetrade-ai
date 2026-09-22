package com.quietstack.voicetrade.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.core.designsystem.OrderPreviewCard
import com.quietstack.voicetrade.core.designsystem.OrderStatusCard
import com.quietstack.voicetrade.core.designsystem.PaperBadge
import com.quietstack.voicetrade.domain.model.Order
import com.quietstack.voicetrade.domain.model.OrderFilter
import com.quietstack.voicetrade.domain.model.OrderPreview
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.usecase.GetOrdersUseCase
import com.quietstack.voicetrade.domain.usecase.PreviewCancelOrderUseCase
import com.quietstack.voicetrade.domain.usecase.ResolvePreviewUseCase
import com.quietstack.voicetrade.ui.common.EmptyState
import com.quietstack.voicetrade.ui.common.InlineError
import com.quietstack.voicetrade.ui.common.LoadingBox
import com.quietstack.voicetrade.ui.common.ScreenScaffold
import com.quietstack.voicetrade.ui.common.messageText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

data class OrdersUiState(
    val openOrders: List<Order> = emptyList(),
    val executions: List<Order> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val cancelPreview: OrderPreview? = null,
    val cancelBusy: Boolean = false,
)

sealed interface OrdersEvent {
    data object Refresh : OrdersEvent
    data object SilentRefresh : OrdersEvent
    data class CancelTapped(val orderId: String) : OrdersEvent
    data object ConfirmCancel : OrdersEvent
    data object DismissCancel : OrdersEvent
}

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val getOrders: GetOrdersUseCase,
    private val previewCancel: PreviewCancelOrderUseCase,
    private val resolvePreview: ResolvePreviewUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(OrdersUiState())
    val state: StateFlow<OrdersUiState> = _state.asStateFlow()

    init {
        load(silent = false)
    }

    fun onEvent(e: OrdersEvent) {
        when (e) {
            OrdersEvent.Refresh -> load(silent = false)
            OrdersEvent.SilentRefresh -> load(silent = true)
            is OrdersEvent.CancelTapped -> viewModelScope.launch {
                previewCancel(e.orderId)
                    .onSuccess { p -> _state.update { it.copy(cancelPreview = p) } }
                    .onFailure { err -> _state.update { it.copy(error = err.asAppError()) } }
            }
            OrdersEvent.ConfirmCancel -> {
                val preview = _state.value.cancelPreview ?: return
                _state.update { it.copy(cancelBusy = true) }
                viewModelScope.launch {
                    resolvePreview.confirm(preview.previewId)
                        .onFailure { err -> _state.update { it.copy(error = err.asAppError()) } }
                    _state.update { it.copy(cancelPreview = null, cancelBusy = false) }
                    load(silent = true)
                }
            }
            OrdersEvent.DismissCancel -> {
                val preview = _state.value.cancelPreview
                _state.update { it.copy(cancelPreview = null) }
                if (preview != null) viewModelScope.launch { resolvePreview.reject(preview.previewId) }
            }
        }
    }

    private fun load(silent: Boolean) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            coroutineScope {
                val open = async { getOrders(OrderFilter.OPEN) }
                val filled = async { getOrders(OrderFilter.FILLED) }
                val o = open.await()
                val f = filled.await()
                _state.update {
                    it.copy(
                        openOrders = o.getOrNull() ?: it.openOrders,
                        executions = f.getOrNull() ?: it.executions,
                        loading = false,
                        error = if (silent) it.error else (o.exceptionOrNull() ?: f.exceptionOrNull())?.asAppError(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(onBack: (() -> Unit)?, viewModel: OrdersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Working orders change on their own, so re-poll while the screen is visible.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(4_000)
                viewModel.onEvent(OrdersEvent.SilentRefresh)
            }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.orders),
        onBack = onBack,
        actions = {
            PaperBadge()
            IconButton(onClick = { viewModel.onEvent(OrdersEvent.Refresh) }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_open)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_filled)) })
            }
            state.error?.let {
                InlineError(messageText(context, it), { viewModel.onEvent(OrdersEvent.Refresh) }, Modifier.padding(16.dp))
            }
            val list = if (tab == 0) state.openOrders else state.executions
            when {
                state.loading && list.isEmpty() -> LoadingBox()
                list.isEmpty() -> EmptyState(stringResource(if (tab == 0) R.string.no_open_orders else R.string.no_fills))
                else -> LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.orderId }) { order ->
                        OrderStatusCard(order, onCancel = { viewModel.onEvent(OrdersEvent.CancelTapped(it.orderId)) })
                    }
                }
            }
        }
    }

    state.cancelPreview?.let { preview ->
        ModalBottomSheet(onDismissRequest = { viewModel.onEvent(OrdersEvent.DismissCancel) }) {
            val seconds = rememberSecondsLeft(preview)
            LaunchedEffect(seconds) { if (seconds <= 0) viewModel.onEvent(OrdersEvent.DismissCancel) }
            Column(Modifier.padding(16.dp)) {
                OrderPreviewCard(
                    preview = preview,
                    state = PreviewState.ACTIVE,
                    secondsLeft = seconds,
                    isSubmitting = state.cancelBusy,
                    onConfirm = { viewModel.onEvent(OrdersEvent.ConfirmCancel) },
                    onCancel = { viewModel.onEvent(OrdersEvent.DismissCancel) },
                )
            }
        }
    }
}

@Composable
private fun rememberSecondsLeft(preview: OrderPreview): Int {
    val clock = remember { Clock.systemUTC() }
    var seconds by remember(preview.previewId) { mutableIntStateOf(secondsUntil(clock, preview)) }
    LaunchedEffect(preview.previewId) {
        while (seconds > 0) {
            delay(500)
            seconds = secondsUntil(clock, preview)
        }
    }
    return seconds
}

private fun secondsUntil(clock: Clock, preview: OrderPreview): Int =
    (((Duration.between(clock.instant(), preview.expiresAt).toMillis()) + 999) / 1000).toInt().coerceAtLeast(0)
