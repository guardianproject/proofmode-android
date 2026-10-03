package org.witness.proofmode.camera.fragments

import androidx.camera.core.ExposureState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.witness.proofmode.camera.R
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Opaque enough to read over a bright scene, since the bar floats on the viewfinder. */
private val ProSurface = Color(0xB3000000) // ~70% black

/**
 * Lays the content out turned a quarter turn, for a landscape-held phone under an
 * activity that is locked to portrait. Unlike a plain `rotate`, the width and height
 * constraints are swapped too, so the content gets the screen's long edge as its
 * width and reports its rotated footprint to the parent.
 */
fun Modifier.quarterTurn(clockwise: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(
            minWidth = constraints.minHeight,
            maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth,
            maxHeight = constraints.maxWidth
        )
    )
    layout(placeable.height, placeable.width) {
        // The layer rotates about the content's centre, so centre it on the footprint.
        placeable.placeWithLayer(
            x = (placeable.height - placeable.width) / 2,
            y = (placeable.width - placeable.height) / 2
        ) { rotationZ = if (clockwise) 90f else -90f }
    }
}

/** Everything the bar and the slider need to know about one setting. */
private class ProItem(
    val setting: ProSetting,
    /** Text shown in the bar: always the current value, whether set by hand or by the camera. */
    val chip: String,
    val manual: Boolean,
    val enabled: Boolean,
    /** Slider position in 0..1, or null while the current value is unknown. */
    val fraction: Float?,
    val valueLabel: String,
    val minLabel: String,
    val maxLabel: String,
    val onFraction: (Float) -> Unit,
    /** Flips between auto and manual; null for settings with no auto state (EV). */
    val onToggleAuto: (() -> Unit)?,
    /**
     * Ruler marks, each one a haptic detent while scrubbing. Continuous settings use a
     * fixed ruler; EV uses its real steps so every tick is an actual change.
     */
    val detents: Int = RulerTicks,
)

/**
 * The Pro bar: a floating row of manual settings, each showing its current value, with
 * a slider that opens underneath for whichever one is tapped.
 *
 * Values set by hand are drawn in the accent colour; values the camera is choosing are
 * white and track the live sensor readout, so the bar always says what the next photo
 * will be taken with. Dragging a slider takes that setting manual; the AUTO pill beside
 * it hands it back. Settings the bound camera can't control manually are left out.
 */
@Composable
fun ProControls(
    pro: ProCameraController,
    exposureState: ExposureState?,
    exposureIndex: Int,
    onExposureIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by pro.settings.collectAsStateWithLifecycle()
    val caps by pro.capabilities.collectAsStateWithLifecycle()
    val live by pro.live.collectAsStateWithLifecycle()
    val haptics = rememberCameraHaptics()
    var selected by remember { mutableStateOf<ProSetting?>(null) }

    val items = buildList {
        val isoRange = caps.isoRange
        val shutterRange = caps.shutterRangeNs
        if (isoRange != null && shutterRange != null) {
            val iso = settings.iso ?: live.iso
            add(ProItem(
                setting = ProSetting.ISO,
                chip = "ISO ${iso ?: "–"}",
                manual = settings.manualExposure,
                enabled = true,
                fraction = iso?.let { logFraction(it.toDouble(), isoRange.first.toDouble(), isoRange.last.toDouble()) },
                valueLabel = iso?.toString() ?: "–",
                minLabel = isoRange.first.toString(),
                maxLabel = isoRange.last.toString(),
                onFraction = { f ->
                    pro.setIso(roundIso(logValue(f, isoRange.first.toDouble(), isoRange.last.toDouble())))
                },
                onToggleAuto = {
                    if (settings.manualExposure) pro.setAutoExposure()
                    else pro.setIso(live.iso ?: isoRange.first)
                },
            ))
            val shutter = settings.shutterNs ?: live.shutterNs
            add(ProItem(
                setting = ProSetting.SHUTTER,
                chip = shutter?.let { shutterLabel(it) + "s" } ?: "–",
                manual = settings.manualExposure,
                enabled = true,
                fraction = shutter?.let { logFraction(it.toDouble(), shutterRange.first.toDouble(), shutterRange.last.toDouble()) },
                valueLabel = shutter?.let { shutterLabel(it) } ?: "–",
                minLabel = shutterLabel(shutterRange.first),
                maxLabel = shutterLabel(shutterRange.last),
                onFraction = { f ->
                    pro.setShutter(logValue(f, shutterRange.first.toDouble(), shutterRange.last.toDouble()).roundToLong())
                },
                onToggleAuto = {
                    if (settings.manualExposure) pro.setAutoExposure()
                    else pro.setShutter(live.shutterNs ?: shutterRange.first)
                },
            ))
        }
        if (exposureState != null && exposureState.isExposureCompensationSupported) {
            val lower = exposureState.exposureCompensationRange.lower
            val upper = exposureState.exposureCompensationRange.upper
            val step = exposureState.exposureCompensationStep
            if (lower < upper) {
                add(ProItem(
                    setting = ProSetting.EV,
                    chip = "EV ${evLabel(exposureIndex, step)}",
                    manual = exposureIndex != 0,
                    // Compensation biases auto-exposure; with ISO and shutter pinned
                    // there is no auto-exposure left for it to bias.
                    enabled = !settings.manualExposure,
                    fraction = (exposureIndex - lower) / (upper - lower).toFloat(),
                    valueLabel = evLabel(exposureIndex, step),
                    minLabel = evLabel(lower, step),
                    maxLabel = evLabel(upper, step),
                    onFraction = { f -> onExposureIndexChange((lower + f * (upper - lower)).roundToInt()) },
                    onToggleAuto = null,
                    detents = (upper - lower + 1).coerceIn(2, MaxRulerTicks),
                ))
            }
        }
        if (caps.manualFocus) {
            val nearest = caps.minFocusDiopters
            val manual = settings.focusDiopters != null
            val focus = settings.focusDiopters ?: live.focusDiopters
            add(ProItem(
                setting = ProSetting.FOCUS,
                chip = if (manual) "MF ${focusLabel(settings.focusDiopters ?: 0f)}" else "AF",
                manual = manual,
                enabled = true,
                // Near on the left, infinity on the right, like a lens barrel scale.
                fraction = focus?.let { (1f - it / nearest).coerceIn(0f, 1f) },
                valueLabel = focus?.let { focusLabel(it) } ?: "–",
                minLabel = focusLabel(nearest),
                maxLabel = focusLabel(0f),
                onFraction = { f -> pro.setFocus((1f - f) * nearest) },
                onToggleAuto = {
                    if (manual) pro.setFocus(null) else pro.setFocus(live.focusDiopters ?: 0f)
                },
            ))
        }
        if (caps.manualWhiteBalance) {
            val min = ProCameraController.WB_MIN_KELVIN
            val max = ProCameraController.WB_MAX_KELVIN
            val manual = settings.whiteBalanceK != null
            val kelvin = settings.whiteBalanceK ?: live.whiteBalanceK
            add(ProItem(
                setting = ProSetting.WHITE_BALANCE,
                chip = if (manual) "WB ${settings.whiteBalanceK}K" else "WB ${stringResource(R.string.pro_auto)}",
                manual = manual,
                enabled = true,
                fraction = kelvin?.let { ((it - min) / (max - min).toFloat()).coerceIn(0f, 1f) },
                valueLabel = kelvin?.let { "${it}K" } ?: "–",
                minLabel = "${min}K",
                maxLabel = "${max}K",
                onFraction = { f -> pro.setWhiteBalance(((min + f * (max - min)) / 50f).roundToInt() * 50) },
                onToggleAuto = {
                    if (manual) pro.setWhiteBalance(null) else pro.setWhiteBalance(live.whiteBalanceK ?: 5000)
                },
            ))
        }
    }
    if (items.isEmpty()) return
    // A lens switch can take the open setting away (front cameras are often fixed-focus).
    val open = items.firstOrNull { it.setting == selected && it.enabled }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(ProSurface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.pro_reset_description),
                tint = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        haptics.tick()
                        pro.resetAll()
                        onExposureIndexChange(0)
                    }
                    .padding(6.dp)
                    .size(18.dp)
            )
            items.forEach { item ->
                val isOpen = item === open
                Text(
                    text = item.chip,
                    maxLines = 1,
                    color = when {
                        isOpen -> CameraBlack
                        !item.enabled -> Color.White.copy(alpha = 0.35f)
                        item.manual -> AccentGreen
                        else -> Color.White
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isOpen) AccentGreen else Color.Transparent)
                        .clickable(enabled = item.enabled) {
                            haptics.tick()
                            selected = if (isOpen) null else item.setting
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = open != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            // Keep drawing the last open item while the panel animates away.
            var lastOpen by remember { mutableStateOf(open) }
            if (open != null) lastOpen = open
            lastOpen?.let { item ->
                ProSliderPanel(
                    item = item,
                    onClose = { selected = null },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ProSliderPanel(item: ProItem, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberCameraHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(ProSurface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item.onToggleAuto?.let { toggle ->
            Text(
                text = stringResource(R.string.pro_auto),
                color = if (item.manual) InactiveWhite else CameraBlack,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (item.manual) Color.White.copy(alpha = 0.12f) else AccentGreen)
                    .clickable {
                        haptics.tick()
                        toggle()
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
            Spacer(Modifier.size(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    item.minLabel,
                    color = InactiveWhite,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                Text(
                    item.valueLabel,
                    color = if (item.manual) AccentGreen else Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.align(Alignment.Center)
                )
                Text(
                    item.maxLabel,
                    color = InactiveWhite,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
            ProRuler(
                fraction = item.fraction,
                manual = item.manual,
                onFraction = item.onFraction,
                detents = item.detents,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )
        }

        Spacer(Modifier.size(6.dp))
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.pro_close_slider_description),
            tint = Color.White,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(6.dp)
                .size(18.dp)
        )
    }
}

private const val RulerTicks = 31

/** Past this many marks the ruler is a grey smear and the ticks a buzz. */
private const val MaxRulerTicks = 61

/**
 * A tick ruler with a taller marker at the current value. Touch position maps
 * straight onto the range, so a tap jumps and a drag scrubs. Each mark the finger
 * crosses gives a haptic tick, like the detents on a lens ring, so the value can be
 * felt changing without looking away from the scene.
 */
@Composable
private fun ProRuler(
    fraction: Float?,
    manual: Boolean,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    detents: Int = RulerTicks,
) {
    val haptics = rememberCameraHaptics()
    val currentOnFraction by rememberUpdatedState(onFraction)
    Canvas(
        modifier = modifier.pointerInput(detents) {
            awaitEachGesture {
                // Claim the down so a scrub that starts over the viewfinder's edge
                // is never read as tap-to-focus.
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val inset = RulerInset.toPx()
                val travel = (size.width - 2 * inset).coerceAtLeast(1f)
                fun fractionAt(x: Float) = ((x - inset) / travel).coerceIn(0f, 1f)
                fun detentAt(f: Float) = (f * (detents - 1)).roundToInt()

                val first = fractionAt(down.position.x)
                // The tap that lands on the ruler is itself a change of value.
                haptics.tick()
                var lastDetent = detentAt(first)
                currentOnFraction(first)
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) {
                        val f = fractionAt(change.position.x)
                        val detent = detentAt(f)
                        if (detent != lastDetent) {
                            haptics.tick()
                            lastDetent = detent
                        }
                        currentOnFraction(f)
                    }
                    change.consume()
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        val inset = RulerInset.toPx()
        val travel = size.width - 2 * inset
        val centerY = size.height / 2f
        val tickHalf = 6.dp.toPx()
        for (i in 0 until detents) {
            val x = inset + travel * i / (detents - 1)
            // Fade the ends, so the ruler reads as a window onto a longer scale.
            val edge = minOf(i, detents - 1 - i).coerceAtMost(4) / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.25f + 0.45f * edge),
                start = Offset(x, centerY - tickHalf),
                end = Offset(x, centerY + tickHalf),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        if (fraction != null) {
            val x = inset + travel * fraction.coerceIn(0f, 1f)
            val markerHalf = 12.dp.toPx()
            drawLine(
                color = if (manual) AccentGreen else Color.White,
                start = Offset(x, centerY - markerHalf),
                end = Offset(x, centerY + markerHalf),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

private val RulerInset = 4.dp

// ISO and shutter are perceived in stops, so both sliders travel logarithmically:
// equal distances are equal changes in exposure.
private fun logFraction(value: Double, min: Double, max: Double): Float =
    (ln(value / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)

private fun logValue(fraction: Float, min: Double, max: Double): Double =
    min * (max / min).pow(fraction.toDouble())

/** Snap to the kind of round numbers ISO is quoted in. */
private fun roundIso(value: Double): Int {
    val step = when {
        value < 200 -> 10
        value < 1000 -> 50
        else -> 100
    }
    return (value / step).roundToInt() * step
}

/** "1/250" for fractions of a second, "0.5" from about a third of a second up. */
private fun shutterLabel(ns: Long): String {
    val seconds = ns / 1e9
    return if (seconds >= 0.3) String.format(Locale.US, "%.1f", seconds)
    else "1/${(1.0 / seconds).roundToInt()}"
}

private fun focusLabel(diopters: Float): String {
    if (diopters < 0.05f) return "∞"
    val metres = 1f / diopters
    return if (metres < 1f) "${(metres * 100).roundToInt()}cm"
    else String.format(Locale.US, "%.1fm", metres)
}
