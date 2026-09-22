package com.quietstack.voicetrade.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.domain.usecase.ObserveSettingsUseCase
import com.quietstack.voicetrade.domain.usecase.SignInUseCase
import com.quietstack.voicetrade.domain.usecase.UpdateSettingsUseCase
import com.quietstack.voicetrade.ui.common.GoogleSignInFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Three intro pages, then sign-in. */
const val SIGN_IN_PAGE = 3

data class OnboardingUiState(
    val page: Int = 0,
    val micPermission: Boolean? = null,
    val isSigningIn: Boolean = false,
    val error: AppError? = null,
    val googleNotConfigured: Boolean = false,
    val googleMessage: String? = null,
)

sealed interface OnboardingEvent {
    data object Next : OnboardingEvent
    data object Back : OnboardingEvent
    data class PermissionResult(val granted: Boolean) : OnboardingEvent
    data class GoogleToken(val idToken: String) : OnboardingEvent
    data class GoogleFailed(val failure: Throwable) : OnboardingEvent
    data object Guest : OnboardingEvent
}

sealed interface OnboardingEffect {
    data object NavigateHome : OnboardingEffect
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val signIn: SignInUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    settings: ObserveSettingsUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _effects = Channel<OnboardingEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        // Someone who already saw the intro (and signed out) goes straight to sign-in.
        viewModelScope.launch {
            if (settings.app.first().onboardingDone) _state.update { it.copy(page = SIGN_IN_PAGE) }
        }
    }

    fun onEvent(e: OnboardingEvent) {
        when (e) {
            OnboardingEvent.Next -> _state.update { it.copy(page = (it.page + 1).coerceAtMost(SIGN_IN_PAGE)) }
            OnboardingEvent.Back -> _state.update { it.copy(page = (it.page - 1).coerceAtLeast(0), error = null) }
            is OnboardingEvent.PermissionResult -> _state.update { it.copy(micPermission = e.granted, page = SIGN_IN_PAGE) }
            is OnboardingEvent.GoogleToken -> run { signIn.google(e.idToken) }
            is OnboardingEvent.GoogleFailed -> when (val f = e.failure) {
                GoogleSignInFailure.Cancelled -> _state.update { it.copy(isSigningIn = false) }
                GoogleSignInFailure.NotConfigured -> _state.update { it.copy(isSigningIn = false, googleNotConfigured = true) }
                else -> _state.update { it.copy(isSigningIn = false, googleMessage = f.message) }
            }
            OnboardingEvent.Guest -> run { signIn.guest() }
        }
    }

    private fun run(call: suspend () -> Result<*>) {
        if (_state.value.isSigningIn) return
        _state.update { it.copy(isSigningIn = true, error = null, googleMessage = null, googleNotConfigured = false) }
        viewModelScope.launch {
            call()
                .onSuccess {
                    updateSettings { s -> s.copy(onboardingDone = true) }
                    _effects.send(OnboardingEffect.NavigateHome)
                }
                .onFailure { err -> _state.update { it.copy(isSigningIn = false, error = err.asAppError()) } }
        }
    }
}
