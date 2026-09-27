package com.quietstack.voicetrade.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.quietstack.voicetrade.R

/** Inter, a typeface built for reading numbers and small text on screens: one variable font file, each weight pulled from it by axis. */
@OptIn(ExperimentalTextApi::class)
private fun weight(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Inter = FontFamily(weight(400), weight(500), weight(600), weight(700), weight(800))

/** The whole app's type scale: five sizes, so the same kind of text is the same size on every screen.
 *  32 a big figure, 22 a screen or section title, 16 a name or card title, 14 body text and values, 12 captions and labels. */
private fun style(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val Title = style(22, FontWeight.Bold, 28)
val VoiceTradeTypography = Typography(
    displayLarge = style(32, FontWeight.Bold, 40),
    displayMedium = style(32, FontWeight.Bold, 40),
    displaySmall = style(32, FontWeight.Bold, 40),
    headlineLarge = Title, headlineMedium = Title, headlineSmall = Title, titleLarge = Title,
    titleMedium = style(16, FontWeight.SemiBold, 22),
    titleSmall = style(14, FontWeight.SemiBold, 20),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(12, FontWeight.Normal, 16),
    labelLarge = style(14, FontWeight.Medium, 20),
    labelMedium = style(12, FontWeight.Medium, 16),
    labelSmall = style(12, FontWeight.Medium, 16),
)
