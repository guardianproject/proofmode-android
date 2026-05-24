package org.witness.proofmode.camera.fragments

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Outer diameter of the primary capture / record control. */
private val ShutterOuter = 76.dp

/** Diameter of the solid inner core when idle. */
private val ShutterCore = 58.dp

/**
 * A Halide/Obscura-style still-capture button: a thin brand-green ring around a
 * solid white core that shrinks on press. When a [label] is supplied (the
 * self-timer countdown value) it is rendered centered in the core.
 */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val coreScale by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "shutterCoreScale"
    )

    Box(
        modifier = modifier
            .size(ShutterOuter)
            .clip(CircleShape)
            .border(width = 3.dp, color = AccentGreen, shape = CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(ShutterCore)
                .scale(coreScale)
                .clip(CircleShape)
                .background(Color.White)
        )
        if (label != null) {
            Text(
                text = label,
                color = CameraBlack,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

/**
 * The video equivalent of [ShutterButton]: the same brand-green ring, with a red
 * inner shape that morphs between a circle (idle) and a rounded square (while
 * recording) — the established camera convention for record / stop.
 */
@Composable
fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 30.dp else ShutterCore,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "recordInnerSize"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isRecording) 8.dp else ShutterCore / 2,
        animationSpec = tween(durationMillis = 220),
        label = "recordCorner"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "recordPressScale"
    )
    val recordRed = Color(0xFFFF3B30)

    Box(
        modifier = modifier
            .size(ShutterOuter)
            .clip(CircleShape)
            .border(width = 3.dp, color = AccentGreen, shape = CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .scale(pressScale)
                .clip(RoundedCornerShape(cornerRadius))
                .background(recordRed)
        )
    }
}

/** Diameter of the secondary circular controls (gallery, lens switch). */
val SecondaryControlSize = 48.dp

/**
 * Shared visual for the small circular controls flanking the shutter: a
 * translucent dark fill with a hairline border, so they read cleanly over any
 * scene without the heavy opaque grey of the previous design.
 */
fun Modifier.secondaryControl(size: Dp = SecondaryControlSize): Modifier = this
    .size(size)
    .clip(CircleShape)
    .background(ControlSurface)
    .border(width = 1.dp, color = ControlBorder, shape = CircleShape)

/** Top edge scrim: darkens the status area for legibility, fading to clear. */
val topScrimBrush: Brush = Brush.verticalGradient(
    colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
)

/** Bottom edge scrim: clear at the top, deepening to near-black at the base. */
val bottomScrimBrush: Brush = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
)
