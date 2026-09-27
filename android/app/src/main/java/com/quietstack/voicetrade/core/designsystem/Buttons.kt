package com.quietstack.voicetrade.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Every button in the app is this rounded rectangle, not Material's default pill, at least 48dp tall. */
val ButtonShape = RoundedCornerShape(12.dp)

private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
private val ButtonMinHeight = 48.dp

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonPadding,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier.heightIn(min = ButtonMinHeight), enabled, ButtonShape, colors, contentPadding = contentPadding, content = content)

@Composable
fun AppTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonPadding,
    content: @Composable RowScope.() -> Unit,
) = FilledTonalButton(onClick, modifier.heightIn(min = ButtonMinHeight), enabled, ButtonShape, contentPadding = contentPadding, content = content)

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    contentPadding: PaddingValues = ButtonPadding,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, modifier.heightIn(min = ButtonMinHeight), enabled, ButtonShape, colors, border = border, contentPadding = contentPadding, content = content)
