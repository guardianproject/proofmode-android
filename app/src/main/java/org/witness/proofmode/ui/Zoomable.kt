package org.witness.proofmode.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * Wraps [content] with gallery-app-style pinch-to-zoom, drag-to-pan (while zoomed) and
 * double-tap-to-reset/zoom gestures. Zooming can be centered on any point under the
 * fingers/tap, not just the center of the box.
 *
 * Drag/pinch is applied to a fixed-size outer box so pointer coordinates stay stable
 * (unaffected by the current zoom transform) while the inner content is what actually
 * scales and translates.
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
            .onSizeChanged { boxSize = it }
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
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    val contentPoint = (centroid - offset) / scale
                    val newOffset = coerceOffset(centroid + pan - contentPoint * newScale, newScale)
                    scale = newScale
                    offset = newOffset
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
