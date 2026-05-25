package org.witness.proofmode.camera.fragments

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Lightweight haptics for the camera UI.
 *
 * Uses the platform [View] feedback API rather than a [android.os.Vibrator], so it
 * needs no `VIBRATE` permission and automatically honours the user's system haptic
 * setting. [HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING] keeps our custom
 * Compose controls feeling alive regardless of the host view's own flag, while the
 * global "touch feedback" preference is still respected.
 */
class CameraHaptics(private val view: View) {
    /** Crisp confirming tick for primary actions: shutter release, record start/stop. */
    fun capture() = view.performHapticFeedback(
        HapticFeedbackConstants.VIRTUAL_KEY,
        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
    )

    /** Subtle tick for discrete selection changes: the camera/video mode toggle. */
    fun tick() = view.performHapticFeedback(
        HapticFeedbackConstants.CLOCK_TICK,
        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
    )
}

/** Remembers a [CameraHaptics] bound to the current Compose host view. */
@Composable
fun rememberCameraHaptics(): CameraHaptics {
    val view = LocalView.current
    return remember(view) { CameraHaptics(view) }
}
