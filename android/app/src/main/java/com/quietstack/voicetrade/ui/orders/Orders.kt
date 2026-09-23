package com.quietstack.voicetrade.ui.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OrderTab(stringResource(R.string.tab_filled), tab == 0, Modifier.weight(1f)) { tab = 0 }
                OrderTab(stringResource(R.string.tab_open), tab == 1, Modifier.weight(1f)) { tab = 1 }
            }
            state.error?.let {
                InlineError(messageText(context, it), { viewModel.onEvent(OrdersEvent.Refresh) }, Modifier.padding(16.dp))
            }
            val list = if (tab == 0) state.executions else state.openOrders
            when {
                state.loading && list.isEmpty() -> LoadingBox()
                list.isEmpty() -> EmptyState(stringResource(if (tab == 0) R.string.no_fills else R.string.no_open_orders))
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
private fun OrderTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
