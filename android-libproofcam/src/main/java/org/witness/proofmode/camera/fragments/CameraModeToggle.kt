package org.witness.proofmode.camera.fragments

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.witness.proofmode.camera.R

enum class CameraModeSelection {
    PHOTO, VIDEO
}

@Composable
fun CameraModeToggle(
    modifier: Modifier = Modifier,
    selectedMode: CameraModeSelection,
    onModeSelected: (CameraModeSelection) -> Unit
) {
    val toggleWidth = 160.dp
    val toggleHeight = 40.dp
    val indicatorWidth = toggleWidth / 2
    val haptics = rememberCameraHaptics()

    // Animate the indicator offset: 0dp for PHOTO (left), half width for VIDEO (right)
    val indicatorOffset by animateDpAsState(
        targetValue = if (selectedMode == CameraModeSelection.PHOTO) 0.dp else indicatorWidth,
        animationSpec = tween(durationMillis = 200),
        label = "indicator_offset"
    )

    Box(
        modifier = modifier
            .width(toggleWidth)
            .height(toggleHeight)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        // Sliding indicator background
        Box(
            modifier = Modifier
                .width(indicatorWidth)
                .fillMaxHeight()
                .offset(x = indicatorOffset)
                .padding(4.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.14f))
        )

        // Labels row
        Row(
            modifier = Modifier
                .width(toggleWidth)
                .height(toggleHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Photo option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (selectedMode != CameraModeSelection.PHOTO) haptics.tick()
                        onModeSelected(CameraModeSelection.PHOTO)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.camera),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = if (selectedMode == CameraModeSelection.PHOTO) AccentGreen else InactiveWhite,
                        fontWeight = if (selectedMode == CameraModeSelection.PHOTO) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 1.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }

            // Video option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (selectedMode != CameraModeSelection.VIDEO) haptics.tick()
                        onModeSelected(CameraModeSelection.VIDEO)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.video),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = if (selectedMode == CameraModeSelection.VIDEO) AccentGreen else InactiveWhite,
                        fontWeight = if (selectedMode == CameraModeSelection.VIDEO) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 1.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
