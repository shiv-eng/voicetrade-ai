package com.quietstack.voicetrade.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.Panel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.designsystem.ActionCardView
import com.quietstack.voicetrade.domain.model.ConversationMessage
import com.quietstack.voicetrade.domain.model.Role
import com.quietstack.voicetrade.domain.model.SessionSummary
import com.quietstack.voicetrade.domain.usecase.ManageHistoryUseCase
import com.quietstack.voicetrade.domain.usecase.ObserveHistoryUseCase
import com.quietstack.voicetrade.ui.common.EmptyState
import com.quietstack.voicetrade.ui.common.LoadingBox
import com.quietstack.voicetrade.ui.common.ScreenScaffold
import com.quietstack.voicetrade.ui.navigation.HistoryDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
private val dayFormat = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())

/** "TODAY", "YESTERDAY", or a short date — matches the section labels people actually think in. */
private fun dayLabel(instant: java.time.Instant): String {
    val zone = ZoneId.systemDefault()
    val day = instant.atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (day) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> dayFormat.format(instant).uppercase()
    }
}

private fun minutesBetween(start: java.time.Instant, end: java.time.Instant?): String? {
    if (end == null) return null
    val mins = java.time.Duration.between(start, end).toMinutes()
    return if (mins <= 0) "< 1 min" else "$mins min"
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    observe: ObserveHistoryUseCase,
    private val manage: ManageHistoryUseCase,
) : ViewModel() {
    val sessions: StateFlow<List<SessionSummary>?> =
        observe.sessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: Long) {
        viewModelScope.launch { manage.delete(id) }
    }
}

@HiltViewModel
class HistoryDetailViewModel @Inject constructor(
    observe: ObserveHistoryUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val sessionId = savedStateHandle.toRoute<HistoryDetail>().sessionId
    val messages: StateFlow<List<ConversationMessage>?> =
        observe.messages(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.history), onBack = onBack) { padding ->
        val list = sessions
        when {
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(stringResource(R.string.history_empty), Modifier.padding(padding))
            else -> {
                val grouped = list.groupBy { dayLabel(it.startedAt) }
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    grouped.forEach { (label, sessions) ->
                        item(key = "h_$label") {
                            Text(
                                label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.1.em, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item(key = "p_$label") {
                            Panel {
                                sessions.forEachIndexed { i, s ->
                                    if (i > 0) Hairline()
                                    Row(
                                        Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        Box(
                                            Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) { Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(s.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            val duration = minutesBetween(s.startedAt, s.endedAt)
                                            Text(
                                                timeFormat.format(s.startedAt) + (duration?.let { " · $it" } ?: ""),
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { viewModel.delete(s.id) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.history_resume_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(onBack: () -> Unit, onContinue: (micGranted: Boolean) -> Unit, viewModel: HistoryDetailViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> onContinue(granted) }
    fun continueTapped() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onContinue(true)
        } else {
            micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    ScreenScaffold(
        title = stringResource(R.string.history_detail),
        onBack = onBack,
        actions = {
            val hasTurns = messages?.any { it.role != Role.SYSTEM && (it.text != null || it.card != null) } == true
            if (hasTurns) {
                androidx.compose.material3.TextButton(onClick = ::continueTapped) {
                    Text(stringResource(R.string.history_continue))
                }
            }
        },
    ) { padding ->
        val list = messages
        if (list.isNullOrEmpty()) {
            if (list != null) EmptyState(stringResource(R.string.history_empty), Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { msg ->
                    val card = msg.card
                    if (card != null) {
                        ActionCardView(card)
                    } else if (msg.text != null) {
                        val user = msg.role == Role.USER
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(0.86f),
                            ) {
                                Text(
                                    msg.text,
                                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    fontStyle = if (msg.role == Role.SYSTEM) FontStyle.Italic else FontStyle.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
