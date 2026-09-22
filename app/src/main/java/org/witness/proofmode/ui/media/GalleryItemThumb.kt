package org.witness.proofmode.ui.media

import android.graphics.RectF
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.witness.proofmode.ui.ASSETS_CORNER_RADIUS
import org.witness.proofmode.ui.LocalSelectionHandler
import org.witness.proofmode.ui.ProofableItem
import org.witness.proofmode.ui.status.ProofStatusOverlay
import org.witness.proofmode.ui.status.badgeLayoutForSize

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryItemThumb(
    item: ProofableItem,
    modifier: Modifier = Modifier,
    contain: Boolean = false,
    corners: RectF = RectF(
        ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS
    ),
    showSelectionBorder: Boolean = true,
    zoomable: Boolean = false,
) {
    val selectionHandler = LocalSelectionHandler.current
    BoxWithConstraints(
        modifier = modifier
            .combinedClickable(
                onClick = {
                    selectionHandler.onProofableItemClick(item)
                },
                onLongClick = {
                    selectionHandler.onProofableItemLongClick(item)
                }
            )
            .border(
                width = 4.dp,
                color = if (showSelectionBorder && selectionHandler.isSelected(item)) Color.Blue else Color.Transparent,
                shape = RoundedCornerShape(
                    corners.left.dp,
                    corners.top.dp,
                    corners.right.dp,
                    corners.bottom.dp
                )
            )
    ) {
        val layout = badgeLayoutForSize(maxWidth, maxHeight)
        ProofItemMedia(
            item = item,
            modifier = Modifier.fillMaxSize(),
            contain = contain,
            corners = corners,
            zoomable = zoomable,
        )
        ProofStatusOverlay(
            item = item,
            badgeLayout = layout,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
