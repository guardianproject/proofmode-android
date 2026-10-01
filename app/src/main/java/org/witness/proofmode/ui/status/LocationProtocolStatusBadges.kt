package org.witness.proofmode.ui.status

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.witness.proofmode.R
import org.witness.proofmode.lp.AutoCaptureLpMarkerResolver
import org.witness.proofmode.lp.LpBadgeUiState
import org.witness.proofmode.lp.LpOffchainBadge
import org.witness.proofmode.lp.LpOnchainBadge
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.ui.ProofStatus
import org.witness.proofmode.ui.ProofableItem

@Composable
internal fun BoxScope.LocationProtocolStatusBadges(
    item: ProofableItem,
    badgeLayout: BadgeLayout,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapshot by key(item.id) {
        remember(item.id) {
            AutoCaptureLpStateRegistry.badgeSnapshotFor(item.id)
        }.collectAsState(
            initial = AutoCaptureLpStateRegistry.currentBadgeSnapshot(item.id),
        )
    }
    var badgeState by remember(item.id) { mutableStateOf(LpBadgeUiState()) }

    LaunchedEffect(item.id, item.proofStatus, snapshot.revision) {
        if (item.proofStatus != ProofStatus.GENERATED) {
            badgeState = LpBadgeUiState()
            return@LaunchedEffect
        }
        badgeState = withContext(Dispatchers.IO) {
            val storage = DefaultStorageProvider(context.applicationContext)
            AutoCaptureLpMarkerResolver.resolve(
                mediaHash = item.id,
                registryState = snapshot.state,
                artifactStore = LocationProtocolArtifactStore(storage),
                storageProvider = storage,
            )
        }
    }

    Row(
        modifier = modifier
            .align(Alignment.TopEnd)
            .padding(badgeLayout.inset),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (badgeState.offchain) {
            LpOffchainBadge.SPINNER -> {
                StatusSpinner(
                    color = Color(0xFF00897B),
                    size = badgeLayout.spinnerSize,
                )
            }

            LpOffchainBadge.FINAL -> {
                Image(
                    painter = painterResource(R.drawable.ic_offchain_signature_badge),
                    contentDescription = "LP off-chain attestation",
                    modifier = Modifier.size(badgeLayout.iconSize),
                )
            }

            LpOffchainBadge.NONE -> Unit
        }
        if (badgeState.offchain != LpOffchainBadge.NONE &&
            badgeState.onchain != LpOnchainBadge.NONE
        ) {
            Spacer(modifier = Modifier.width(2.dp))
        }
        when (badgeState.onchain) {
            LpOnchainBadge.SPINNER -> {
                StatusSpinner(
                    color = Color(0xFFFFA000),
                    size = badgeLayout.spinnerSize,
                )
            }

            LpOnchainBadge.PENDING -> {
                Image(
                    painter = painterResource(R.drawable.ic_onchain_signature_badge),
                    contentDescription = "LP on-chain pending",
                    colorFilter = ColorFilter.tint(Color(0xFFFFA000)),
                    modifier = Modifier.size(badgeLayout.iconSize),
                )
            }

            LpOnchainBadge.CONFIRMED -> {
                Image(
                    painter = painterResource(R.drawable.ic_onchain_signature_badge),
                    contentDescription = "LP on-chain confirmed",
                    modifier = Modifier.size(badgeLayout.iconSize),
                )
            }

            LpOnchainBadge.NONE -> Unit
        }
    }
}
