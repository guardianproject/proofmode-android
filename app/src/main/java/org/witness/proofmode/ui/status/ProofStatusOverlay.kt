package org.witness.proofmode.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.R
import org.witness.proofmode.c2pa.ValidationState
import org.witness.proofmode.ui.ProofStatus
import org.witness.proofmode.ui.ProofableItem

internal enum class OverlayPriority { NONE, PENDING, GENERATING, C2PA }

internal fun overlayPriority(
    proofStatus: ProofStatus,
    c2paState: ValidationState,
): OverlayPriority = when (proofStatus) {
    ProofStatus.PENDING -> OverlayPriority.PENDING
    ProofStatus.GENERATING -> OverlayPriority.GENERATING
    ProofStatus.GENERATED -> if (c2paState != ValidationState.INVALID) {
        OverlayPriority.C2PA
    } else OverlayPriority.NONE
}

internal fun lpBadgeAllowed(lpActive: Boolean, proofStatus: ProofStatus): Boolean =
    lpActive && proofStatus == ProofStatus.GENERATED

@Composable
internal fun BoxScope.ProofStatusOverlay(
    item: ProofableItem,
    badgeLayout: BadgeLayout,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val lifecycleOwner = LocalLifecycleOwner.current
        var lpActive by remember { mutableStateOf(FeatureFlags.lpActive) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    lpActive = FeatureFlags.lpActive
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val c2paState = rememberC2paStatus(item)

        when (overlayPriority(item.proofStatus, c2paState)) {
            OverlayPriority.PENDING -> {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_proof_pending),
                    contentDescription = "Proof pending",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(badgeLayout.inset)
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(2.dp)
                        )
                        .padding(1.dp)
                        .size(badgeLayout.iconSize)
                )
            }

            OverlayPriority.GENERATING -> {
                StatusSpinner(
                    color = Color.White,
                    size = badgeLayout.spinnerSize,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(badgeLayout.inset)
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(2.dp)
                        )
                        .padding(4.dp)
                )
            }

            OverlayPriority.C2PA -> C2paStatusBadge(badgeLayout)
            OverlayPriority.NONE -> Unit
        }

        if (lpBadgeAllowed(lpActive, item.proofStatus)) {
            LocationProtocolStatusBadges(item, badgeLayout)
        }

        FilebaseStatusBadge(item, badgeLayout)
    }
}
