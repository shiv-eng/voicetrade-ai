package com.quietstack.voicetrade.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quietstack.voicetrade.domain.model.AgentState

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, AWAITING, ERROR }

fun AgentState.toOrbState(): OrbState = when (this) {
    AgentState.IDLE -> OrbState.IDLE
    AgentState.LISTENING -> OrbState.LISTENING
    AgentState.THINKING -> OrbState.THINKING
    AgentState.SPEAKING -> OrbState.SPEAKING
    AgentState.AWAITING_CONFIRMATION -> OrbState.AWAITING
}

/**
 * The hero control. Grey and still when idle, blue and pulsing with mic level when listening,
 * a purple shimmer while thinking, green waves while speaking, an amber countdown ring while
 * waiting for a confirmation, red on error.
 *
 * @param level 0f..1f audio level that drives the pulse/waves
 * @param countdown 0f..1f remaining time of the confirmation window, drawn only in [OrbState.AWAITING]
 */
@Composable
fun MicOrb(
    state: OrbState,
    level: Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    muted: Boolean = false,
    countdown: Float = 1f,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val extra = MaterialTheme.extra
    val color = tint ?: when (state) {
        OrbState.IDLE -> extra.orbIdle
        OrbState.LISTENING -> extra.orbListening
        OrbState.THINKING -> extra.orbThinking
        OrbState.SPEAKING -> extra.orbSpeaking
        OrbState.AWAITING -> extra.orbAwaiting
        OrbState.ERROR -> extra.orbError
    }
    val smoothLevel by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "level")
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val wave by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "wave",
    )
    val breathe by transition.animateFloat(
        0.96f, 1.04f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe",
    )

    val clickable = if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick) else Modifier
    Box(
        modifier = modifier
            .size(size)
            .then(clickable)
            .semantics {
                this.contentDescription = contentDescription
                if (onClick != null) role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val c = center
            val core = this.size.minDimension * 0.30f
            val max = this.size.minDimension / 2f
            when (state) {
                OrbState.LISTENING -> {
                    val r = core + (max - core) * (0.25f + 0.75f * smoothLevel)
                    drawCircle(color.copy(alpha = 0.22f), radius = r, center = c)
                    drawCircle(color.copy(alpha = 0.12f), radius = r + (max - r) * 0.5f, center = c)
                }
                OrbState.SPEAKING -> for (i in 0..2) {
                    val phase = (wave + i / 3f) % 1f
                    val r = core + (max - core) * (phase * (0.5f + smoothLevel))
                    drawCircle(color.copy(alpha = (1f - phase) * 0.35f), radius = r.coerceAtMost(max), center = c)
                }
                OrbState.THINKING -> rotate(spin, c) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, color), c),
                        startAngle = 0f,
                        sweepAngle = 300f,
                        useCenter = false,
                        topLeft = Offset(c.x - max * 0.86f, c.y - max * 0.86f),
                        size = Size(max * 1.72f, max * 1.72f),
                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                OrbState.AWAITING -> {
                    val ring = max * 0.86f
                    drawCircle(color.copy(alpha = 0.18f), radius = ring, center = c, style = Stroke(10.dp.toPx()))
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * countdown.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(c.x - ring, c.y - ring),
                        size = Size(ring * 2, ring * 2),
                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                OrbState.ERROR -> drawCircle(color.copy(alpha = 0.2f), radius = max * 0.85f, center = c)
                OrbState.IDLE -> drawCircle(color.copy(alpha = 0.12f), radius = max * 0.8f * breathe, center = c)
            }
            drawCircle(color, radius = core * (if (state == OrbState.IDLE) 1f else breathe), center = c)
        }
        Icon(
            imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.24f),
        )
    }
}

