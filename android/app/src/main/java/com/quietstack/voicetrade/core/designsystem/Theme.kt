package com.quietstack.voicetrade.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.quietstack.voicetrade.domain.model.ThemeMode

/** Semantic colours that Material 3 has no slot for. Every pair keeps 4.5:1 on its surface. */
@Immutable
data class ExtraColors(
    val gain: Color,
    val loss: Color,
    val paper: Color,
    val onPaper: Color,
    val orbIdle: Color,
    val orbListening: Color,
    val orbThinking: Color,
    val orbSpeaking: Color,
    val orbAwaiting: Color,
    val orbError: Color,
)

private val LightExtra = ExtraColors(
    gain = Color(0xFF15803D),
    loss = Color(0xFFB91C1C),
    paper = Color(0xFFFEF3C7),
    onPaper = Color(0xFF7C2D12),
    orbIdle = Color(0xFF8A94A6),
    orbListening = Color(0xFF2563EB),
    orbThinking = Color(0xFF7C3AED),
    orbSpeaking = Color(0xFF16A34A),
    orbAwaiting = Color(0xFFD97706),
    orbError = Color(0xFFDC2626),
)

private val DarkExtra = ExtraColors(
    gain = Color(0xFF4ADE80),
    loss = Color(0xFFF87171),
    paper = Color(0xFF3B2A07),
    onPaper = Color(0xFFFCD34D),
    orbIdle = Color(0xFF2DD4BF),
    orbListening = Color(0xFF60A5FA),
    orbThinking = Color(0xFFA78BFA),
    orbSpeaking = Color(0xFF4ADE80),
    orbAwaiting = Color(0xFFFBBF24),
    orbError = Color(0xFFF87171),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF4A635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EBE6),
    onSecondaryContainer = Color(0xFF00201D),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xFFF6F8FB),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFE6EBF2),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDF1F7),
    outline = Color(0xFF64748B),
    error = Color(0xFFB91C1C),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF0F3D3A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCDE8E3),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF1B2538),
    onSurfaceVariant = Color(0xFFA9B4C6),
    surfaceContainerLowest = Color(0xFF070C16),
    surfaceContainerLow = Color(0xFF0F1727),
    surfaceContainer = Color(0xFF131C30),
    surfaceContainerHigh = Color(0xFF1B2538),
    outline = Color(0xFF7C8AA1),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF5B1A1A),
    onErrorContainer = Color(0xFFFECACA),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

val MaterialTheme.extra: ExtraColors
    @Composable get() = LocalExtraColors.current

/** Whether the app is showing its dark palette: the user's choice, or the phone's own setting for "System". */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Material 3's shape scale, with the corner radii the app's cards, chips and sheets use. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VoiceTradeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    largeText: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = themeMode.isDark()
    val density = LocalDensity.current
    val scaled = if (largeText) Density(density.density, density.fontScale * 1.3f) else density
    CompositionLocalProvider(
        LocalExtraColors provides if (dark) DarkExtra else LightExtra,
        LocalDensity provides scaled,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = VoiceTradeTypography, shapes = AppShapes, content = content)
    }
}
