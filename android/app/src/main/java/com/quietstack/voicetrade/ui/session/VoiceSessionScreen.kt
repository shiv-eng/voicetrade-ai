package com.quietstack.voicetrade.ui.session

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.designsystem.ActionCardView
import com.quietstack.voicetrade.core.designsystem.CardActions
import com.quietstack.voicetrade.core.designsystem.ErrorCard
import com.quietstack.voicetrade.core.designsystem.MicOrb
import com.quietstack.voicetrade.core.designsystem.OrbState
import com.quietstack.voicetrade.core.designsystem.PaperBadge
import com.quietstack.voicetrade.core.designsystem.StatusChip
import com.quietstack.voicetrade.core.designsystem.extra
import com.quietstack.voicetrade.core.designsystem.toOrbState
import com.quietstack.voicetrade.domain.model.ActionCard
import com.quietstack.voicetrade.domain.model.AgentState
import com.quietstack.voicetrade.domain.model.ConversationMessage
import com.quietstack.voicetrade.domain.model.PreviewState
import com.quietstack.voicetrade.domain.model.Role
import com.quietstack.voicetrade.ui.common.BiometricGate
import com.quietstack.voicetrade.ui.common.messageText

@Composable
fun VoiceSessionScreen(
    micGranted: Boolean,
    prompt: String?,
    resumeSessionId: Long? = null,
    onBack: () -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenStock: (Long) -> Unit = {},
    onOpenIpos: () -> Unit = {},
    onOpenIpo: (String, String, String?) -> Unit = { _, _, _ -> },
    viewModel: VoiceSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) { viewModel.onEvent(VoiceSessionEvent.Start(micGranted, prompt, resumeSessionId)) }
    BackHandler { viewModel.onEvent(VoiceSessionEvent.End) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                VoiceSessionEffect.Haptic -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                VoiceSessionEffect.NavigateBack -> onBack()
                is VoiceSessionEffect.ShowSnackbar -> snackbar.showSnackbar(
                    effect.message ?: effect.error?.let { messageText(context, it) }.orEmpty(),
                )
                is VoiceSessionEffect.RequestBiometric -> {
                    val activity = context as? FragmentActivity
                    if (activity == null) {
                        viewModel.onEvent(VoiceSessionEvent.BiometricPassed(effect.previewId))
                    } else {
                        BiometricGate.prompt(
                            activity = activity,
                            title = context.getString(R.string.biometric_title),
                            subtitle = context.getString(R.string.biometric_subtitle),
                            onSuccess = { viewModel.onEvent(VoiceSessionEvent.BiometricPassed(effect.previewId)) },
                            onFailure = { /* stay on the preview; the user can retry or cancel */ },
                        )
                    }
                }
            }
        }
    }

    val orbState = orbStateOf(state)
    val glow = orbColor(orbState)
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to glow.copy(alpha = 0.16f), 0.45f to MaterialTheme.colorScheme.background),
            ),
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                TopBar(state)
                Hero(
                    state = state, orb = orbState, compact = imeVisible || state.messages.isNotEmpty(),
                    onTap = { viewModel.onEvent(VoiceSessionEvent.ToggleMute) },
                )
                VoiceStatusPill(
                    state = state,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                (state.connection as? Connection.Error)?.let {
                    ErrorCard(
                        message = messageText(context, it.error), retryable = true,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        onRetry = { viewModel.onEvent(VoiceSessionEvent.Retry) },
                    )
                }

                Transcript(
                    state = state,
                    modifier = Modifier.weight(1f),
                    onSuggest = { viewModel.onEvent(VoiceSessionEvent.SendText(it)) },
                    actions = CardActions(
                        onBuy = { viewModel.onEvent(VoiceSessionEvent.SendText("Buy ${it.name}")) },
                        onSell = { viewModel.onEvent(VoiceSessionEvent.SendText("Sell ${it.name}")) },
                        onAddToWatchlist = { viewModel.onEvent(VoiceSessionEvent.AddToWatchlist(it)) },
                        onOpenPortfolio = onOpenPortfolio,
                        onConfirm = { viewModel.onEvent(VoiceSessionEvent.ConfirmPreview(it.previewId)) },
                        onReject = { viewModel.onEvent(VoiceSessionEvent.RejectPreview(it.previewId)) },
                        onCancelOrder = { viewModel.onEvent(VoiceSessionEvent.SendText("Cancel my ${it.instrument.name} order")) },
                        onPick = { viewModel.onEvent(VoiceSessionEvent.SendText("I mean ${it.name} on ${it.exchange}")) },
                        onRetry = { viewModel.onEvent(VoiceSessionEvent.Retry) },
                        onOpenStock = { onOpenStock(it.conid) },
                        onOpenIpos = onOpenIpos,
                        onOpenIpo = { onOpenIpo(it.symbol, it.series, it.name) },
                        onOpenIpoDetail = { onOpenIpo(it.symbol, it.series, it.name) },
                    ),
                )

                SnackbarHost(snackbar)
                ResumeChip(state, onResume = { viewModel.onEvent(VoiceSessionEvent.ResumeAnswer) })
                ControlBar(
                    state = state,
                    showInput = state.keyboardOpen || state.textOnly,
                    onSend = { viewModel.onEvent(VoiceSessionEvent.SendText(it)) },
                    onMute = { viewModel.onEvent(VoiceSessionEvent.ToggleMute) },
                    onPause = { viewModel.onEvent(VoiceSessionEvent.TogglePause) },
                    onKeyboard = { viewModel.onEvent(VoiceSessionEvent.ToggleKeyboard) },
                    onEnd = { viewModel.onEvent(VoiceSessionEvent.End) },
                )
            }
        }
    }
}

/**
 * The server says "speaking" the moment the model writes words, but the voice arrives a second or two later.
 * With Agora audio we only call it speaking while sound is really playing.
 */
private fun VoiceSessionUiState.shownAgentState(): AgentState = when {
    agoraAudio && agentState == AgentState.SPEAKING && !agentAudible -> if (spokeThisTurn) AgentState.LISTENING else AgentState.THINKING
    else -> agentState
}

private fun VoiceSessionUiState.mirasVoiceIsPlaying(): Boolean =
    if (agoraAudio) agentAudible else agentState == AgentState.SPEAKING

private fun orbStateOf(state: VoiceSessionUiState): OrbState {
    val agent = state.shownAgentState()
    return when {
        state.connection is Connection.Error -> OrbState.ERROR
        state.connection != Connection.Connected -> OrbState.THINKING
        state.textOnly -> if (agent == AgentState.AWAITING_CONFIRMATION) OrbState.AWAITING else if (agent == AgentState.THINKING) OrbState.THINKING else OrbState.IDLE
        state.isMuted -> OrbState.IDLE
        else -> agent.toOrbState()
    }
}

@Composable
private fun orbColor(state: OrbState): Color {
    val extra = MaterialTheme.extra
    return when (state) {
        OrbState.IDLE -> extra.orbIdle
        OrbState.LISTENING -> extra.orbListening
        OrbState.THINKING -> extra.orbThinking
        OrbState.SPEAKING -> extra.orbSpeaking
        OrbState.AWAITING -> extra.orbAwaiting
        OrbState.ERROR -> extra.orbError
    }
}

@Composable
private fun TopBar(state: VoiceSessionUiState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Mira", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        PaperBadge()
        if (state.killSwitchOn) StatusChip(stringResource(R.string.kill_switch_on), MaterialTheme.extra.loss)
        Box(Modifier.weight(1f))
        val (label, color) = when (state.connection) {
            Connection.Idle, Connection.Connecting -> stringResource(R.string.connecting) to MaterialTheme.extra.orbAwaiting
            Connection.Connected -> stringResource(R.string.live) to MaterialTheme.extra.gain
            is Connection.Error -> stringResource(R.string.error) to MaterialTheme.extra.loss
        }
        StatusChip(label, color)
    }
}

@Composable
private fun Hero(state: VoiceSessionUiState, orb: OrbState, compact: Boolean, onTap: () -> Unit) {
    val agent = state.shownAgentState()
    val label = when {
        state.connection is Connection.Error -> stringResource(R.string.orb_error)
        state.connection != Connection.Connected -> stringResource(R.string.connecting)
        agent == AgentState.AWAITING_CONFIRMATION -> stringResource(R.string.orb_awaiting)
        state.textOnly && agent != AgentState.THINKING -> stringResource(R.string.orb_type_to_mira)
        state.isPaused -> stringResource(R.string.orb_paused)
        state.isMuted -> stringResource(R.string.orb_muted)
        agent == AgentState.LISTENING -> stringResource(R.string.orb_listening)
        agent == AgentState.THINKING -> stringResource(R.string.orb_thinking)
        agent == AgentState.SPEAKING -> stringResource(R.string.orb_speaking)
        else -> stringResource(R.string.orb_idle)
    }
    val caption = state.liveCaption.takeIf { it.isNotBlank() && agent == AgentState.LISTENING && !state.isMuted }
    Column(Modifier.fillMaxWidth().animateContentSize().padding(top = if (compact) 0.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MicOrb(
            state = orb,
            level = state.micLevel,
            contentDescription = stringResource(R.string.orb_cd, label),
            size = if (compact) 104.dp else 216.dp,
            muted = state.isMuted || state.textOnly,
            countdown = state.previewSecondsLeft / 60f,
            onClick = onTap.takeIf { state.connection == Connection.Connected && !state.textOnly },
        )
        Text(
            caption?.let { "“$it”" } ?: label,
            style = if (caption != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            fontStyle = if (caption != null) FontStyle.Italic else FontStyle.Normal,
            color = if (caption != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp).semantics { contentDescription = label },
        )
    }
}

/** A quiet one-line status instead of a banner: mic off, or voice not available on this server. */
@Composable
private fun VoiceStatusPill(state: VoiceSessionUiState, onOpenSettings: () -> Unit) {
    if (state.connection != Connection.Connected) return
    val (text, action) = when {
        state.micDenied -> stringResource(R.string.pill_mic_off) to true
        state.textOnly -> stringResource(R.string.pill_voice_unavailable) to false
        else -> return
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.let { if (action) it.clickable(onClick = onOpenSettings) else it },
        ) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Transcript(state: VoiceSessionUiState, actions: CardActions, onSuggest: (String) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    if (state.messages.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
            Text(
                stringResource(if (state.textOnly) R.string.session_hint_text else R.string.session_hint_voice),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            if (state.connection == Connection.Connected) {
                listOf(R.string.sugg_quote, R.string.sugg_own, R.string.sugg_cash, R.string.sugg_watch, R.string.sugg_buy).forEach { res ->
                    val text = stringResource(res)
                    AssistChip(
                        onClick = { onSuggest(text) },
                        label = { Text(text) },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(vertical = 3.dp),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    )
                }
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val latestAgentId = state.messages.lastOrNull { it.role == Role.AGENT && it.text != null }?.id
        items(state.messages, key = { it.id }) { msg -> MessageItem(msg, state, actions, paced = msg.id == latestAgentId && !state.textOnly) }
    }
}

@Composable
private fun MessageItem(msg: ConversationMessage, state: VoiceSessionUiState, actions: CardActions, paced: Boolean) {
    val card = msg.card
    if (card != null) {
        val isActivePreview = card is ActionCard.PreviewCard && card.state == PreviewState.ACTIVE
        ActionCardView(
            card = card,
            secondsLeft = if (isActivePreview) state.previewSecondsLeft else 0,
            isSubmitting = isActivePreview && state.isConfirming,
            actions = actions,
        )
        return
    }
    val text = msg.text ?: return
    when (msg.role) {
        Role.SYSTEM -> Text(
            text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth(0.84f),
            ) {
                Text(
                    text, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (msg.isFinal) FontStyle.Normal else FontStyle.Italic,
                )
            }
        }
        // Mira's words are plain text on the page (no bubble), revealed at speaking pace while her voice plays.
        Role.AGENT -> Row(Modifier.fillMaxWidth().padding(end = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.padding(top = 6.dp).size(26.dp)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mic, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            if (paced) {
                PacedText(text, msg.isFinal, state.mirasVoiceIsPlaying(), MaterialTheme.typography.titleMedium, Modifier.weight(1f))
            } else {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private const val REVEAL_CHARS_PER_SECOND = 14f

/**
 * Shows [text] a little at a time, but only while [voicePlaying]: the words follow the voice instead of racing ahead
 * of it. If the voice never starts (or has ended) the rest appears once the answer is complete.
 */
@Composable
private fun PacedText(text: String, isFinal: Boolean, voicePlaying: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    var shown by remember { mutableIntStateOf(0) }
    val target by rememberUpdatedState(text)
    val playing by rememberUpdatedState(voicePlaying)
    val final by rememberUpdatedState(isFinal)
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var carry = 0f
        var idleMs = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000L
            last = now
            val full = target.length
            if (shown > full) shown = full
            if (shown < full) {
                if (playing) {
                    idleMs = 0
                    carry += dt * REVEAL_CHARS_PER_SECOND / 1000f
                    val step = carry.toInt()
                    if (step > 0) {
                        carry -= step
                        shown = minOf(full, shown + step)
                    }
                } else {
                    idleMs += dt
                    // The voice has stopped (or never came): don't leave words hidden for long.
                    if (final && idleMs > 2500) shown = full
                }
            }
        }
    }
    val visible = target.take(shown).let { part ->
        // finish the word being spoken instead of cutting it in half
        val end = target.indexOf(' ', part.length).let { if (it < 0) target.length else it }
        if (shown in 1 until target.length) target.substring(0, end) else part
    }
    Text(
        visible.ifEmpty { "…" }, modifier = modifier, style = style,
        color = if (visible.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

/** Shown when Mira is paused or was cut off: one tap and she says the answer again from the start. */
@Composable
private fun ResumeChip(state: VoiceSessionUiState, onResume: () -> Unit) {
    if (!state.canResume && !state.isPaused) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center) {
        androidx.compose.material3.AssistChip(
            onClick = onResume,
            label = { Text(stringResource(if (state.isPaused) R.string.paused_resume else R.string.interrupted_resume)) },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
        )
    }
}

/** One floating bar: type a message (when typing is on), mute, keyboard and end. */
@Composable
private fun ControlBar(
    state: VoiceSessionUiState,
    showInput: Boolean,
    onSend: (String) -> Unit,
    onMute: () -> Unit,
    onPause: () -> Unit,
    onKeyboard: () -> Unit,
    onEnd: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
            keyboard?.hide() // so a resulting order preview is visible, not hidden behind the keyboard
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showInput) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.type_message)) }, singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                    FilledIconButton(onClick = ::submit, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onMute, modifier = Modifier.size(56.dp), enabled = !state.textOnly) {
                    Icon(
                        if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = stringResource(if (state.isMuted) R.string.unmute else R.string.mute),
                    )
                }
                FilledTonalIconButton(onClick = onPause, modifier = Modifier.size(56.dp), enabled = !state.textOnly && state.connection == Connection.Connected) {
                    Icon(
                        if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = stringResource(if (state.isPaused) R.string.resume else R.string.pause),
                    )
                }
                FilledTonalIconButton(onClick = onKeyboard, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Keyboard, contentDescription = stringResource(R.string.keyboard))
                }
                FilledIconButton(
                    onClick = onEnd,
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.end))
                }
            }
        }
    }
}
