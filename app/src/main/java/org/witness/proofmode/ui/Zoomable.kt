package org.witness.proofmode.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Wraps [content] with gallery-app-style pinch-to-zoom, drag-to-pan (while zoomed) and
 * double-tap-to-reset/zoom gestures. Zooming can be centered on any point under the
 * fingers/tap, not just the center of the box.
 *
 * Drag/pinch is applied to a fixed-size outer box so pointer coordinates stay stable
 * (unaffected by the current zoom transform) while the inner content is what actually
 * scales and translates.
 *
 * The gesture is only claimed once there is something to do with it: a pinch, or a drag
 * while zoomed in. A one-finger drag at 1x is left unconsumed so enclosing gestures (in
 * the single-asset viewer: drag up/down to show/hide the metadata panel, swipe sideways
 * between assets) keep working on top of the image.
 */
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 5f,
    doubleTapScale: Float = 3f,
    content: @Composable BoxScope.() -> Unit
) {
    var scale by remember { mutableFloatStateOf(minScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()

    fun coerceOffset(target: Offset, atScale: Float): Offset {
        val maxX = (boxSize.width * (atScale - 1f)).coerceAtLeast(0f)
        val maxY = (boxSize.height * (atScale - 1f)).coerceAtLeast(0f)
        return Offset(target.x.coerceIn(-maxX, 0f), target.y.coerceIn(-maxY, 0f))
    }

    fun animateTo(targetScale: Float, targetOffset: Offset) {
        val startScale = scale
        val startOffset = offset
        coroutineScope.launch {
            animate(0f, 1f, animationSpec = tween(250)) { fraction, _ ->
                scale = startScale + (targetScale - startScale) * fraction
                offset = startOffset + (targetOffset - startOffset) * fraction
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                boxSize = size
                // The box resizes when the viewer expands/collapses; re-clamp so an
                // already panned image can't be left outside the new bounds.
                offset = coerceOffset(offset, scale)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapPoint ->
                        if (scale > minScale + 0.01f) {
                            animateTo(minScale, Offset.Zero)
                        } else {
                            val targetScale = doubleTapScale.coerceIn(minScale, maxScale)
                            val contentPoint = (tapPoint - offset) / scale
                            val targetOffset = coerceOffset(
                                tapPoint - contentPoint * targetScale,
                                targetScale
                            )
                            animateTo(targetScale, targetOffset)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    var zoomAccumulator = 1f
                    var panAccumulator = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // Someone else (an enclosing drag) took over the gesture.
                        if (event.changes.fastAny { it.isConsumed }) break

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (!pastTouchSlop) {
                            zoomAccumulator *= zoomChange
                            panAccumulator += panChange
                            val centroidSize = event.calculateCentroidSize(useCurrent = false)
                            val zoomMotion = abs(1 - zoomAccumulator) * centroidSize
                            if (zoomMotion > touchSlop || panAccumulator.getDistance() > touchSlop) {
                                pastTouchSlop = true
                            }
                        }

                        if (pastTouchSlop) {
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val moved = zoomChange != 1f || panChange != Offset.Zero
                            val pinching = event.changes.count { it.pressed } > 1
                            // centroid is unspecified once the last pointer is up - using
                            // it then would turn the offset into NaN and blank the image.
                            if (centroid.isSpecified && moved && (pinching || scale > minScale)) {
                                val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
                                val contentPoint = (centroid - offset) / scale
                                scale = newScale
                                offset = coerceOffset(
                                    centroid + panChange - contentPoint * newScale,
                                    newScale
                                )
                                event.changes.fastForEach {
                                    if (it.positionChanged()) it.consume()
                                }
                            }
                        }
                    } while (event.changes.fastAny { it.pressed })
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f)
                ),
            content = content
        )
    }
}
