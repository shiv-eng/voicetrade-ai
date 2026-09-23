package com.quietstack.voicetrade.ui.onboarding

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietstack.voicetrade.BuildConfig
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.designsystem.MicOrb
import com.quietstack.voicetrade.core.designsystem.OrbState
import com.quietstack.voicetrade.core.designsystem.PaperBadge
import com.quietstack.voicetrade.core.designsystem.extra
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
        Box(
            Modifier.size(72.dp).background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f))),
                RoundedCornerShape(22.dp),
            ).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Text(stringResource(R.string.signin_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, letterSpacing = (-0.03).em)
        Text(stringResource(R.string.signin_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
        ) {
            Column {
                BenefitRow(Icons.Filled.AccountBalanceWallet, MaterialTheme.colorScheme.primary, "₹10,00,000 + $10,000", stringResource(R.string.signin_benefit_wallet_body))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                BenefitRow(Icons.Filled.RecordVoiceOver, MaterialTheme.colorScheme.secondary, stringResource(R.string.signin_benefit_orders), stringResource(R.string.signin_benefit_orders_body))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                BenefitRow(Icons.Filled.Fingerprint, MaterialTheme.extra.orbAwaiting, stringResource(R.string.biometric), stringResource(R.string.signin_benefit_biometric_body))
            }
        }
        if (state.micPermission == false) {
            Text(stringResource(R.string.mic_denied_note), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { InlineError(messageText(context, it), null) }
        if (state.googleNotConfigured) InlineError(stringResource(R.string.google_not_configured), null)
        state.googleMessage?.let { InlineError(stringResource(R.string.google_failed, it), null) }
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, tint: Color, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                Brush.radialGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 1400f,
                ),
            ).statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            MicOrb(OrbState.IDLE, level = 0f, contentDescription = stringResource(R.string.app_name), size = 170.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                "Talk to the market.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center, letterSpacing = (-0.03).em,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.onb1_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lang_choose_title), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                Text(stringResource(R.string.lang_choose_subtitle), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            LanguagePill(stringResource(R.string.lang_hindi_only), "Hindi", filled = true, onClick = { onPick("hi") })
            Spacer(Modifier.height(12.dp))
            LanguagePill(stringResource(R.string.lang_english_only), "English", filled = false, onClick = { onPick("en") })
            Spacer(Modifier.height(16.dp))
            Text(
                "मीरा हिन्दी और English दोनों में बात करती है  ·  you can change this later",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun LanguagePill(label: String, caption: String, filled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
        Row(Modifier.padding(horizontal = 22.dp).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
            )
            Text(
                caption, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (filled) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                tint = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
