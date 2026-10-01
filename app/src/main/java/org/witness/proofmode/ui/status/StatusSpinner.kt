package org.witness.proofmode.ui.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class BadgeLayout(
    val iconSize: Dp,
    val inset: Dp,
    val spinnerSize: Dp,
)

internal fun badgeLayoutForSize(width: Dp, height: Dp): BadgeLayout =
    if (width < 80.dp || height < 80.dp) BadgeLayout(16.dp, 2.dp, 16.dp)
    else BadgeLayout(24.dp, 8.dp, 18.dp)

@Composable
internal fun StatusSpinner(
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = color,
            strokeWidth = 2.dp,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
