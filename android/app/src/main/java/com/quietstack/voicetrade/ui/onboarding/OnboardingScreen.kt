package com.quietstack.voicetrade.ui.onboarding

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietstack.voicetrade.BuildConfig
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.designsystem.PaperBadge
import com.quietstack.voicetrade.ui.common.GoogleSignIn
import com.quietstack.voicetrade.ui.common.InlineError
import com.quietstack.voicetrade.ui.common.messageText
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!com.quietstack.voicetrade.core.i18n.AppLocale.chosen(context)) {
        LanguageChoice(onPick = { lang ->
            com.quietstack.voicetrade.core.i18n.AppLocale.set(context, lang)
            (context as? Activity)?.recreate()
        })
        return
    }
    val scope = rememberCoroutineScope()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onEvent(OnboardingEvent.PermissionResult(result[Manifest.permission.RECORD_AUDIO] == true))
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { if (it == OnboardingEffect.NavigateHome) onDone() }
    }
    BackHandler(enabled = state.page > 0) { viewModel.onEvent(OnboardingEvent.Back) }

    fun googleTapped() {
        val activity = context as? Activity ?: return
        scope.launch {
            GoogleSignIn.idToken(activity)
                .onSuccess { viewModel.onEvent(OnboardingEvent.GoogleToken(it)) }
                .onFailure { viewModel.onEvent(OnboardingEvent.GoogleFailed(it)) }
        }
    }

    val backdrop = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), MaterialTheme.colorScheme.background))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().background(backdrop).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                PaperBadge()
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (state.page) {
                    0 -> IntroPage(Icons.Filled.GraphicEq, R.string.onb1_title, R.string.onb1_body)
                    1 -> IntroPage(Icons.Filled.VerifiedUser, R.string.onb2_title, R.string.onb2_body, disclaimer = true)
                    2 -> IntroPage(Icons.Filled.Mic, R.string.onb3_title, R.string.onb3_body)
                    else -> SignInPage(state, context)
                }
            }
            PageDots(state.page)
            Spacer(Modifier.height(16.dp))
            when (state.page) {
                0, 1 -> Button(
                    onClick = { viewModel.onEvent(OnboardingEvent.Next) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) { Text(stringResource(R.string.next)) }
                2 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val wanted = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissions.launch(wanted.toTypedArray())
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    ) { Text(stringResource(R.string.allow_microphone)) }
                    TextButton(
                        onClick = { viewModel.onEvent(OnboardingEvent.PermissionResult(false)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.not_now)) }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::googleTapped,
                        enabled = !state.isSigningIn,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
                    ) {
                        if (state.isSigningIn) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFF1F1F1F))
                        } else {
                            Text("G", fontWeight = FontWeight.ExtraBold, color = Color(0xFF4285F4), modifier = Modifier.padding(end = 10.dp))
                            Text(stringResource(R.string.continue_with_google), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (BuildConfig.ALLOW_GUEST) {
                        TextButton(onClick = { viewModel.onEvent(OnboardingEvent.Guest) }, enabled = !state.isSigningIn, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.continue_as_guest))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroPage(icon: ImageVector, title: Int, body: Int, disclaimer: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(28.dp),
        )
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (disclaimer) {
            Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SignInPage(state: OnboardingUiState, context: android.content.Context) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(
            Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(28.dp),
        )
        Text(stringResource(R.string.signin_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(stringResource(R.string.signin_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.micPermission == false) {
            Text(stringResource(R.string.mic_denied_note), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { InlineError(messageText(context, it), null) }
        if (state.googleNotConfigured) InlineError(stringResource(R.string.google_not_configured), null)
        state.googleMessage?.let { InlineError(stringResource(R.string.google_failed, it), null) }
    }
}

@Composable
private fun PageDots(page: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(SIGN_IN_PAGE + 1) { i ->
            Spacer(
                Modifier.padding(4.dp).size(if (i == page) 10.dp else 8.dp).background(
                    if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    CircleShape,
                ),
            )
        }
    }
}


/** The very first thing a new user sees: which language should the app speak? */
@Composable
private fun LanguageChoice(onPick: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), MaterialTheme.colorScheme.background)),
            ).statusBarsPadding().navigationBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            Text("अपनी भाषा चुनें", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Choose your language", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            androidx.compose.material3.Button(onClick = { onPick("hi") }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text("हिन्दी", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.OutlinedButton(onClick = { onPick("en") }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text("English", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "मीरा हिन्दी और English दोनों में बात करती है  ·  Mira speaks both Hindi and English",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
