package com.quietstack.voicetrade.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import com.quietstack.voicetrade.core.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.extra
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quietstack.voicetrade.BuildConfig
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.common.AppError
import com.quietstack.voicetrade.core.common.asAppError
import com.quietstack.voicetrade.domain.model.AppSettings
import com.quietstack.voicetrade.domain.model.ConnectionConfig
import com.quietstack.voicetrade.domain.model.Language
import com.quietstack.voicetrade.domain.model.ThemeMode
import com.quietstack.voicetrade.domain.model.VoiceGender
import com.quietstack.voicetrade.domain.usecase.ManageHistoryUseCase
import com.quietstack.voicetrade.domain.usecase.ObserveSettingsUseCase
import com.quietstack.voicetrade.domain.usecase.SignOutUseCase
import com.quietstack.voicetrade.core.designsystem.SymbolAvatar
import com.quietstack.voicetrade.domain.usecase.UpdateSettingsUseCase
import com.quietstack.voicetrade.ui.common.ScreenScaffold
import com.quietstack.voicetrade.ui.common.messageText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val connection: ConnectionConfig = ConnectionConfig(),
)

sealed interface SettingsEvent {
    data class SetLanguage(val value: Language) : SettingsEvent
    data class SetVoice(val value: VoiceGender) : SettingsEvent
    data class SetRate(val value: Float) : SettingsEvent
    data class SetTheme(val value: ThemeMode) : SettingsEvent
    data object Unlink : SettingsEvent
    data object DeleteHistory : SettingsEvent
}

sealed interface SettingsEffect {
    data object ShowSaved : SettingsEffect
    data class ShowError(val error: AppError) : SettingsEffect
    data object NavigateToOnboarding : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val observe: ObserveSettingsUseCase,
    private val update: UpdateSettingsUseCase,
    private val history: ManageHistoryUseCase,
    private val signOut: SignOutUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        observe.app.onEach { s -> _state.update { it.copy(settings = s) } }.launchIn(viewModelScope)
        observe.connection.onEach { c -> _state.update { it.copy(connection = c) } }.launchIn(viewModelScope)
    }

    fun onEvent(e: SettingsEvent) {
        when (e) {
            is SettingsEvent.SetLanguage -> save { it.copy(language = e.value) }
            is SettingsEvent.SetVoice -> save { it.copy(voice = e.value) }
            is SettingsEvent.SetRate -> save { it.copy(speechRate = e.value) }
            is SettingsEvent.SetTheme -> save { it.copy(theme = e.value) }
            SettingsEvent.Unlink -> viewModelScope.launch {
                signOut()
                _effects.send(SettingsEffect.NavigateToOnboarding)
            }
            SettingsEvent.DeleteHistory -> viewModelScope.launch {
                history.deleteAll()
                _effects.send(SettingsEffect.ShowSaved)
            }
        }
    }

    private fun save(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { update(transform) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onUnlinked: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmUnlink by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.saved)

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.ShowSaved -> snackbar.showSnackbar(saved)
                is SettingsEffect.ShowError -> snackbar.showSnackbar(messageText(context, effect.error))
                SettingsEffect.NavigateToOnboarding -> onUnlinked()
            }
        }
    }

    ScreenScaffold(title = stringResource(R.string.settings), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val s = state.settings

                state.connection.profile?.let { p ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SymbolAvatar(p.name.ifBlank { "U" }, size = 52.dp)
                        Column {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            p.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    HorizontalDivider()
                }
                Section(R.string.app_language)
                Panel {
                Column(Modifier.padding(16.dp)) {
                    val current = com.quietstack.voicetrade.core.i18n.AppLocale.effective(context)
                    ChipRow(
                        options = listOf("en" to R.string.lang_english_only, "hi" to R.string.lang_hindi_only),
                        selected = current,
                        onSelect = { lang ->
                            if (lang != current) {
                                com.quietstack.voicetrade.core.i18n.AppLocale.set(context, lang)
                                (context as? android.app.Activity)?.recreate()
                            }
                        },
                    )
                }
                }
                Section(R.string.settings_voice)
                Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.speech_speed, "%.2f".format(s.speechRate)))
                    Slider(
                        value = s.speechRate,
                        onValueChange = { viewModel.onEvent(SettingsEvent.SetRate((it * 20).toInt() / 20f)) },
                        valueRange = 0.75f..1.5f,
                    )
                }
                }

                Section(R.string.settings_safety)
                Panel {
                Column(Modifier.padding(16.dp)) {
                    var briefing by remember { mutableStateOf(com.quietstack.voicetrade.notifications.Scheduler.briefingEnabled(context)) }
                    SwitchRow(
                        title = tr("Morning market briefing"),
                        subtitle = "A notification at 8:30 am with Nifty, Sensex, your holdings and IPOs today. Tap it to hear Mira read it.",
                        checked = briefing,
                        onChecked = { briefing = it; com.quietstack.voicetrade.notifications.Scheduler.setBriefingEnabled(context, it) },
                    )
                }
                }

                Section(R.string.settings_appearance)
                Panel {
                Column(Modifier.padding(16.dp)) {
                    ChipRow(
                        options = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark),
                        selected = s.theme,
                        onSelect = { viewModel.onEvent(SettingsEvent.SetTheme(it)) },
                    )
                }
                }

                Section(R.string.settings_data)
                Panel {
                Column {
                    DataRow(stringResource(R.string.delete_history), Icons.Filled.Delete, danger = true, onClick = { confirmDelete = true })
                    Hairline()
                    DataRow(stringResource(R.string.unlink_device), Icons.AutoMirrored.Filled.Logout, danger = false, onClick = { confirmUnlink = true })
                }
                }

                Section(R.string.about)
                Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
            SnackbarHost(snackbar)
        }
    }

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text(stringResource(R.string.unlink_device)) },
            text = { Text(stringResource(R.string.unlink_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmUnlink = false; viewModel.onEvent(SettingsEvent.Unlink) }) { Text(stringResource(R.string.unlink)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_history)) },
            text = { Text(stringResource(R.string.delete_history_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.onEvent(SettingsEvent.DeleteHistory) }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.1.em,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** A tappable settings row: icon, label, and a chevron unless it's a destructive (red) action. */
@Composable
private fun DataRow(label: String, icon: ImageVector, danger: Boolean, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.extra.loss else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))
        if (!danger) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

