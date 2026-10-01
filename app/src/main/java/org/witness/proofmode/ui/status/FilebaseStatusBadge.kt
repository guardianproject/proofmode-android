package org.witness.proofmode.ui.status

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.witness.proofmode.R
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import org.witness.proofmode.storage.proofset.ProofSetUploader
import org.witness.proofmode.ui.ProofableItem

internal enum class FilebaseIndicator { NONE, SPINNER, BADGE }

internal fun filebaseIndicator(occupancy: Int, hasSuccessSidecar: Boolean): FilebaseIndicator = when {
    occupancy > 0 -> FilebaseIndicator.SPINNER
    hasSuccessSidecar -> FilebaseIndicator.BADGE
    else -> FilebaseIndicator.NONE
}

@Composable
internal fun BoxScope.FilebaseStatusBadge(
    item: ProofableItem,
    badgeLayout: BadgeLayout,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val occupancy by key(item.id) {
        remember(item.id) {
            ProofSetUploader.occupancyFor(item.id)
        }.collectAsState(
            initial = ProofSetUploader.occupancyCount(item.id),
        )
    }
    var pinned by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, occupancy) {
        pinned = withContext(Dispatchers.IO) {
            FilebaseSidecarContract.hasSuccessSidecar(
                DefaultStorageProvider(context.applicationContext), item.id,
            )
        }
    }

    when (filebaseIndicator(occupancy, pinned)) {
        FilebaseIndicator.SPINNER -> {
            StatusSpinner(
                color = Color.Black,
                size = badgeLayout.spinnerSize,
                modifier = modifier
                    .align(Alignment.BottomStart)
                    .padding(badgeLayout.inset),
            )
        }

        FilebaseIndicator.BADGE -> {
            Image(
                painter = painterResource(R.drawable.ic_filebase_pinned),
                contentDescription = "Filebase pin",
                modifier = modifier
                    .align(Alignment.BottomStart)
                    .padding(badgeLayout.inset)
                    .size(badgeLayout.iconSize),
            )
        }

        FilebaseIndicator.NONE -> Unit
    }
}
