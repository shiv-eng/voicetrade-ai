package com.quietstack.voicetrade.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.quietstack.voicetrade.R

/** Manrope, the redesign's typeface: one variable font file, each weight pulled from it by axis. */
@OptIn(ExperimentalTextApi::class)
private fun weight(w: Int) = Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Manrope = FontFamily(weight(400), weight(500), weight(600), weight(700), weight(800))

/** Material's default type scale, re-set in Manrope. Individual screens already choose their own weight
 * and size for headlines and big figures; this is what everything else (labels, body text) falls back to. */
private val base = Typography()
val VoiceTradeTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Manrope),
    displayMedium = base.displayMedium.copy(fontFamily = Manrope),
    displaySmall = base.displaySmall.copy(fontFamily = Manrope),
    headlineLarge = base.headlineLarge.copy(fontFamily = Manrope),
    headlineMedium = base.headlineMedium.copy(fontFamily = Manrope),
    headlineSmall = base.headlineSmall.copy(fontFamily = Manrope),
    titleLarge = base.titleLarge.copy(fontFamily = Manrope),
    titleMedium = base.titleMedium.copy(fontFamily = Manrope),
    titleSmall = base.titleSmall.copy(fontFamily = Manrope),
    bodyLarge = base.bodyLarge.copy(fontFamily = Manrope),
    bodyMedium = base.bodyMedium.copy(fontFamily = Manrope),
    bodySmall = base.bodySmall.copy(fontFamily = Manrope),
    labelLarge = base.labelLarge.copy(fontFamily = Manrope),
    labelMedium = base.labelMedium.copy(fontFamily = Manrope),
    labelSmall = base.labelSmall.copy(fontFamily = Manrope),
)
