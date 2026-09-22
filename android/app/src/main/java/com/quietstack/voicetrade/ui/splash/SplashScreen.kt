package com.quietstack.voicetrade.ui.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.designsystem.MicOrb
import com.quietstack.voicetrade.core.designsystem.OrbState
import com.quietstack.voicetrade.core.designsystem.PaperBadge
import com.quietstack.voicetrade.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(private val settings: ObserveSettingsUseCase) : ViewModel() {
    /** Home if the device is already linked to a backend (or demo), otherwise onboarding. */
    suspend fun isLinked(): Boolean = settings.connection.first().isLinked
}

@Composable
fun SplashScreen(onLinked: () -> Unit, onNeedsOnboarding: () -> Unit, viewModel: SplashViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) {
        delay(700)
        if (viewModel.isLinked()) onLinked() else onNeedsOnboarding()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            MicOrb(OrbState.LISTENING, level = 0.3f, contentDescription = stringResource(R.string.app_name), size = 140.dp)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            PaperBadge()
        }
    }
}
