package com.quietstack.voicetrade

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.core.designsystem.VoiceTradeTheme
import com.quietstack.voicetrade.domain.model.AppSettings
import com.quietstack.voicetrade.domain.usecase.ObserveSettingsUseCase
import com.quietstack.voicetrade.ui.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(settings: ObserveSettingsUseCase) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settings.app.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** False once the user signs out, or the server rejects the device token. */
    val signedIn: StateFlow<Boolean> =
        settings.connection.map { it.isLinked }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
}

/** Single activity. It extends FragmentActivity because BiometricPrompt needs one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.quietstack.voicetrade.core.i18n.AppLocale.wrap(newBase))
    }

    private val launchPrompt = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val launchOpen = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        readLaunch(intent)
    }

    private fun readLaunch(intent: android.content.Intent?) {
        intent?.getStringExtra(com.quietstack.voicetrade.notifications.Notifications.EXTRA_PROMPT)?.let { launchPrompt.value = it }
        intent?.getStringExtra(com.quietstack.voicetrade.notifications.Notifications.EXTRA_OPEN)?.let { launchOpen.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readLaunch(intent)
        setContent {
            val app: AppViewModel = hiltViewModel()
            val settings by app.settings.collectAsStateWithLifecycle()
            VoiceTradeTheme(themeMode = settings.theme) {
                AppNavHost(
                    signedIn = app.signedIn.collectAsStateWithLifecycle().value,
                    launchPrompt = launchPrompt.value, launchOpen = launchOpen.value,
                    onLaunchConsumed = { launchPrompt.value = null; launchOpen.value = null },
                )
            }
        }
    }
}
