package com.quietstack.voicetrade.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.domain.model.AccountSummary
import com.quietstack.voicetrade.domain.model.Pnl
import com.quietstack.voicetrade.domain.model.Position
import com.quietstack.voicetrade.domain.model.WatchRow
import com.quietstack.voicetrade.domain.usecase.GetPortfolioUseCase
import com.quietstack.voicetrade.domain.usecase.GetWatchlistUseCase
import com.quietstack.voicetrade.domain.usecase.ObserveNetworkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val account: AccountSummary? = null,
    val pnl: Pnl? = null,
    val positions: List<Position> = emptyList(),
    val watchlist: List<WatchRow> = emptyList(),
    val market: com.quietstack.voicetrade.domain.model.MarketOverview? = null,
    val loaded: Boolean = false,
    val name: String? = null,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val error: AppError? = null,
)

sealed interface HomeEvent {
    data object Refresh : HomeEvent
    /** [prompt] is a suggestion the user tapped; it is sent to Mira as soon as the session connects. */
    data class StartSession(val micGranted: Boolean, val prompt: String? = null) : HomeEvent
}

sealed interface HomeEffect {
    data class NavigateSession(val micGranted: Boolean, val prompt: String?) : HomeEffect
    data class ShowError(val error: AppError) : HomeEffect
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getPortfolio: GetPortfolioUseCase,
    private val getWatchlist: GetWatchlistUseCase,
    private val research: com.quietstack.voicetrade.domain.repository.ResearchRepository,
    observeNetwork: ObserveNetworkUseCase,
    settings: com.quietstack.voicetrade.domain.usecase.ObserveSettingsUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        observeNetwork().onEach { online ->
            val wasOffline = _state.value.isOffline
            _state.update { it.copy(isOffline = !online) }
            if (online && wasOffline) refresh()
        }.launchIn(viewModelScope)
        settings.connection.onEach { c -> _state.update { it.copy(name = c.profile?.name?.substringBefore(' ')) } }.launchIn(viewModelScope)
        refresh()
    }

    fun onEvent(e: HomeEvent) {
        when (e) {
            HomeEvent.Refresh -> refresh()
            is HomeEvent.StartSession -> viewModelScope.launch {
                if (_state.value.isOffline) _effects.send(HomeEffect.ShowError(AppError.Network()))
                else _effects.send(HomeEffect.NavigateSession(e.micGranted, e.prompt))
            }
        }
    }

    private fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            coroutineScope {
                val portfolio = async { getPortfolio() }
                val watch = async { getWatchlist() }
                val market = async { research.marketOverview() }
                portfolio.await()
                    .onSuccess { snap ->
                        _state.update {
                            it.copy(account = snap.summary, pnl = snap.pnl, positions = snap.positions.sortedByDescending { p -> p.marketValue }, error = null)
                        }
                    }
                    .onFailure { err -> _state.update { it.copy(error = err.asAppError()) } }
                watch.await().onSuccess { rows -> _state.update { it.copy(watchlist = rows) } }
                market.await().onSuccess { m -> _state.update { it.copy(market = m) } }
            }
            _state.update { it.copy(isRefreshing = false, loaded = true) }
        }
    }
}
