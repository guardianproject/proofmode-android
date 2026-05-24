package org.witness.proofmode.camera.fragments

import androidx.compose.ui.graphics.Color

// Legacy deep-green brand color (retained as part of the module's public surface).
val ColorPrimary = Color(0xFF083b00)

// ---------------------------------------------------------------------------
// ProofMode camera palette — a clean monochrome "pro" look with a single
// brand-green accent. The green matches the main app (colorPrimary / colorAccent
// = #79E176) and is used sparingly: the shutter ring, active control states and
// the focus reticle. Everything else stays strictly black & white.
// ---------------------------------------------------------------------------

/** Primary brand green accent (matches app `colorPrimary` / `colorAccent`). */
val AccentGreen = Color(0xFF79E176)

/** Slightly deeper green for pressed / secondary accent moments. */
val AccentGreenDark = Color(0xFF3CB371)

/** True black viewfinder backdrop. */
val CameraBlack = Color(0xFF000000)

/** Dark surface for sheets, dialogs and elevated chrome. */
val CameraSurface = Color(0xFF121212)
val CameraSurfaceVariant = Color(0xFF1E1E1E)

/** Translucent dark fill for secondary circular controls. */
val ControlSurface = Color(0x59000000) // ~35% black

/** Hairline border for secondary controls and framed thumbnails. */
val ControlBorder = Color(0x80FFFFFF) // ~50% white

/** De-emphasised icon/label tint for inactive controls. */
val InactiveWhite = Color(0x99FFFFFF) // ~60% white

/** Thin grid overlay lines. */
val GridLine = Color(0x4DFFFFFF) // ~30% white
