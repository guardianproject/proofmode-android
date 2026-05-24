package org.witness.proofmode.camera.fragments

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A focused dark theme for the camera experience. Surfaces are near-black, text
 * is white and the single accent is the ProofMode brand green. Wrapping the
 * camera in this theme keeps the modal sheets, switches, sliders and dialogs
 * visually consistent with the on-viewfinder chrome instead of falling back to
 * the platform's default (often purple-tinted) Material colors.
 */
private val CameraColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = CameraBlack,
    primaryContainer = AccentGreenDark,
    onPrimaryContainer = CameraBlack,
    secondary = AccentGreen,
    onSecondary = CameraBlack,
    background = CameraBlack,
    onBackground = Color.White,
    surface = CameraSurface,
    onSurface = Color.White,
    surfaceVariant = CameraSurfaceVariant,
    onSurfaceVariant = InactiveWhite,
    outline = ControlBorder,
    error = Color(0xFFFF5252),
    onError = CameraBlack,
)

@Composable
fun CameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CameraColorScheme,
        content = content
    )
}
