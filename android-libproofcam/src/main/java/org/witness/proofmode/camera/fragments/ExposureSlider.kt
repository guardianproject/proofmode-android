package org.witness.proofmode.camera.fragments

import android.util.Rational
import androidx.camera.core.ExposureState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.witness.proofmode.camera.R
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Travel of the exposure track: about a thumb's reach without shifting grip. */
private val ExposureTrackHeight = 168.dp

/** Width of the touch target around the hairline track. */
private val ExposureTouchWidth = 48.dp

/** Diameter of the sun thumb that rides the track. */
private val ExposureThumbSize = 30.dp

/**
 * How long the exposure slider lingers after the last focus tap or drag before
 * fading out again. Long enough to reach for it, short enough that it never
 * competes with the scene.
 */
const val ExposureSliderTimeoutMs = 4000L

/**
 * A vertical, iOS/Halide-style exposure-compensation control that rides the left
 * edge of the viewfinder: drag the sun up to brighten, down to darken.
 *
 * It renders nothing when the bound camera cannot compensate exposure, so callers
 * can place it unconditionally. [index] is the CameraX exposure-compensation
 * *index* (not EV); the EV readout above the track is derived from
 * [ExposureState.getExposureCompensationStep].
 *
 * The control is stateless: it reports each discrete step through [onIndexChange]
 * and every touch through [onInteraction], which the screens use to keep their
 * auto-hide timer alive.
 */
@Composable
fun ExposureSlider(
    exposureState: ExposureState?,
    index: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onInteraction: () -> Unit = {},
) {
    if (exposureState == null || !exposureState.isExposureCompensationSupported) return
    val lower = exposureState.exposureCompensationRange.lower
    val upper = exposureState.exposureCompensationRange.upper
    if (lower >= upper) return

    val haptics = rememberCameraHaptics()
    val label = stringResource(R.string.change_exposure_compensation)
    val span = (upper - lower).toFloat()
    val fraction = ((index - lower) / span).coerceIn(0f, 1f)
    // Nudged rather than snapped: each discrete step lands with a little settle,
    // which reads as a physical detent when dragging quickly.
    val thumbOffset by animateDpAsState(
        targetValue = ExposureTrackHeight * (1f - fraction),
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "exposureThumbOffset"
    )

    // The drag lambda captures state at composition; these keep it reading the
    // live index and callbacks without re-installing the pointer handler.
    val currentIndex by rememberUpdatedState(index)
    val currentOnIndexChange by rememberUpdatedState(onIndexChange)
    val currentOnInteraction by rememberUpdatedState(onInteraction)
    // Sub-step drag position, so slow drags accumulate instead of being rounded away.
    var dragPosition by remember { mutableFloatStateOf(index.toFloat()) }

    Column(
        modifier = modifier
            .width(ExposureTouchWidth)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = evLabel(index, exposureState.exposureCompensationStep),
            color = if (index == 0) Color.White else AccentGreen,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(ControlSurface)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .width(ExposureTouchWidth)
                .height(ExposureTrackHeight + ExposureThumbSize)
                .pointerInput(lower, upper) {
                    // One full track traversal spans the whole compensation range,
                    // so the gain adapts to devices that expose 12 steps or 48.
                    val stepPx = ExposureTrackHeight.toPx() / span
                    awaitEachGesture {
                        // The viewfinder's tap-to-focus handler sits underneath this
                        // control and also gets hit-tested. Claiming the down here is
                        // what stops a slider drag from refocusing on the left edge.
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        dragPosition = currentIndex.toFloat()
                        currentOnInteraction()

                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // Read the delta before consuming: positionChange() reports
                            // zero once the change has been claimed.
                            val dy = change.positionChange().y
                            change.consume()
                            if (dy != 0f) {
                                // Screen y grows downward; dragging up must brighten.
                                dragPosition = (dragPosition - dy / stepPx)
                                    .coerceIn(lower.toFloat(), upper.toFloat())
                                val next = dragPosition.roundToInt()
                                if (next != currentIndex) {
                                    haptics.tick()
                                    currentOnIndexChange(next)
                                }
                                currentOnInteraction()
                            }
                        } while (event.changes.any { it.pressed })

                        currentOnInteraction()
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            ExposureTrack(
                fraction = fraction,
                zeroFraction = if (lower <= 0 && upper >= 0) (0 - lower) / span else null,
                modifier = Modifier.matchParentSize()
            )

            SunThumb(
                modifier = Modifier
                    .offset(y = thumbOffset)
                    .size(ExposureThumbSize)
            )
        }
    }
}

/**
 * The hairline track behind the thumb: a dim full-length rail, a brighter segment
 * running from the neutral (0 EV) mark to the current value, and a tick at neutral
 * so "back to auto" is findable without looking at the readout.
 */
@Composable
private fun ExposureTrack(
    fraction: Float,
    zeroFraction: Float?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val inset = ExposureThumbSize.toPx() / 2f
        val top = inset
        val bottom = size.height - inset
        val travel = bottom - top
        val centerX = size.width / 2f
        val stroke = 2.dp.toPx()

        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(centerX, top),
            end = Offset(centerX, bottom),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        if (zeroFraction != null) {
            val zeroY = bottom - travel * zeroFraction
            val thumbY = bottom - travel * fraction
            drawLine(
                color = AccentGreen,
                start = Offset(centerX, zeroY),
                end = Offset(centerX, thumbY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            val tick = 5.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.7f),
                start = Offset(centerX - tick, zeroY),
                end = Offset(centerX + tick, zeroY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * The thumb: a small drawn sun on a soft dark halo, so it stays visible against a
 * blown-out sky as readily as against shadow.
 */
@Composable
private fun SunThumb(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val extent = size.minDimension
        drawCircle(color = Color.Black.copy(alpha = 0.4f), radius = extent / 2f, center = center)
        drawCircle(color = AccentGreen, radius = extent * 0.22f, center = center)

        val rayInner = extent * 0.32f
        val rayOuter = extent * 0.44f
        val rayStroke = extent * 0.07f
        repeat(8) { i ->
            val angle = (PI / 4.0 * i).toFloat()
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                color = AccentGreen,
                start = Offset(center.x + dx * rayInner, center.y + dy * rayInner),
                end = Offset(center.x + dx * rayOuter, center.y + dy * rayOuter),
                strokeWidth = rayStroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Turns a compensation index into the EV readout photographers expect ("+1.0",
 * "-0.7"). [step] is the EV each index is worth on this device — a third or a
 * half stop, typically — so the index alone is not comparable across cameras.
 */
private fun evLabel(index: Int, step: Rational?): String {
    val ev = step?.let { index * it.toDouble() } ?: index.toDouble()
    return String.format(Locale.US, "%+.1f", ev)
}
