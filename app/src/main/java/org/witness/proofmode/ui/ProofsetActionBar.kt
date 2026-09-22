package org.witness.proofmode.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.ProofModeApp
import org.witness.proofmode.R
import org.witness.proofmode.filebase.FilebaseOnDemandCandidate
import org.witness.proofmode.filebase.FilebaseOnDemandCoordinator
import org.witness.proofmode.filebase.FilebaseOnDemandItem
import org.witness.proofmode.filebase.FilebaseOnDemandPreflight
import org.witness.proofmode.lp.LpManualLeg
import org.witness.proofmode.lp.enqueueManualAttestForShareProof
import org.witness.proofmode.lp.hasArtifactForManualLeg
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.share.FilebaseSettingsActivity
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import org.witness.proofmode.storage.proofset.ProofSetMediaSource
import org.witness.proofmode.storage.proofset.ProofSetUploader

fun actionBarItems(
    anySelected: Boolean,
    selectedItems: List<ProofableItem>,
    currentItem: ProofableItem?,
): List<ProofableItem> = when {
    anySelected -> selectedItems
    currentItem != null -> listOf(currentItem)
    else -> emptyList()
}

fun lpActionIconsVisible(lpActive: Boolean): Boolean = lpActive

fun lpUrisForManualEnqueue(items: List<ProofableItem>): List<Uri> =
    items.filter { it.proofStatus == ProofStatus.GENERATED && it.uri != Uri.EMPTY }.map { it.uri }

private fun enqueueLpManualAttest(context: Context, items: List<ProofableItem>, leg: LpManualLeg) {
    val generatedUris = lpUrisForManualEnqueue(items)
    if (generatedUris.isEmpty()) {
        Toast.makeText(context, R.string.lp_attest_nothing_to_queue, Toast.LENGTH_LONG).show()
        return
    }
    LocationProtocolPlugin.requireApplicationScope().launch {
        enqueueManualAttestForShareProof(
            appContext = context.applicationContext,
            uris = generatedUris,
            leg = leg,
            hashCache = mutableMapOf(),
            storage = DefaultStorageProvider(context.applicationContext),
        )
    }
}

private fun showFilebaseNotConfiguredDialog(context: Context) {
    AlertDialog.Builder(context)
        .setTitle(R.string.filebase_not_configured_title)
        .setMessage(R.string.filebase_not_configured_message)
        .setPositiveButton(R.string.filebase_not_configured_configure) { _, _ ->
            context.startActivity(Intent(context, FilebaseSettingsActivity::class.java))
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun enqueueFilebaseBatch(context: Context, queue: List<FilebaseOnDemandItem>): Job {
    val app = context.applicationContext as ProofModeApp
    return FilebaseOnDemandCoordinator.issueOnDemandFlush(
        scope = app.applicationCoroutineScope(),
        appContext = app.applicationContext,
        items = queue,
    )
}

internal fun showFilebaseOversizeConsentDialog(
    context: Context,
    effect: FilebaseActionBarTapEffect.ShowOversizeConsent,
    releaseInFlight: () -> Unit,
) {
    AlertDialog.Builder(context)
        .setTitle(R.string.filebase_upload_oversize_title)
        .setMessage(
            context.getString(
                R.string.filebase_upload_oversize_batch_message,
                effect.oversizeCount,
            ),
        )
        .setPositiveButton(R.string.filebase_upload_oversize_confirm) { _, _ ->
            enqueueFilebaseBatch(context, effect.confirmQueue)
                .invokeOnCompletion { releaseInFlight() }
        }
        .setNegativeButton(R.string.filebase_upload_oversize_cancel) { _, _ -> releaseInFlight() }
        .setCancelable(true)
        .setOnCancelListener { releaseInFlight() }
        .show()
}

private fun applyFilebaseTapEffect(
    context: Context,
    effect: FilebaseActionBarTapEffect,
    releaseInFlight: () -> Unit,
) {
    when (effect) {
        FilebaseActionBarTapEffect.ShowNotConfiguredDialog -> showFilebaseNotConfiguredDialog(context)
        FilebaseActionBarTapEffect.ShowProofNotReadyToast -> {
            Toast.makeText(context, R.string.filebase_proof_not_ready, Toast.LENGTH_LONG).show()
        }
        FilebaseActionBarTapEffect.NoOp -> Unit
        is FilebaseActionBarTapEffect.EnqueueBatch -> {
            enqueueFilebaseBatch(context, effect.queue)
                .invokeOnCompletion { releaseInFlight() }
        }
        is FilebaseActionBarTapEffect.ShowOversizeConsent ->
            showFilebaseOversizeConsentDialog(context, effect, releaseInFlight)
    }
}

@Composable
fun ProofsetActionBar(
    items: List<ProofableItem>,
    showCancel: Boolean,
    onCancel: () -> Unit,
    onShare: (List<ProofableItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var lpActive by remember { mutableStateOf(FeatureFlags.lpActive) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) lpActive = FeatureFlags.lpActive
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val filebaseInFlight = remember { AtomicBoolean(false) }
    var filebaseIssuing by remember { mutableStateOf(false) }
    var filebaseInSlot by remember { mutableStateOf(false) }
    var onchainBusy by remember { mutableStateOf(false) }
    var offchainBusy by remember { mutableStateOf(false) }
    val itemIdsKey = items.joinToString { it.id }
    LaunchedEffect(itemIdsKey) {
        // hasArtifactForManualLeg stats the proof directory per item, so the reads move off the
        // composition dispatcher; only the resulting flags are applied on it.
        suspend fun refreshBusy() {
            val (inSlot, onchain, offchain) = withContext(Dispatchers.IO) {
                val storage = DefaultStorageProvider(context.applicationContext)
                Triple(
                    items.any { item ->
                        item.id.isNotBlank() && ProofSetUploader.occupancyCount(item.id) > 0
                    },
                    items.any { item ->
                        lpActionBarBusy(
                            AutoCaptureLpStateRegistry.getState(item.id).onchain,
                            hasArtifactForManualLeg(storage, item.id, LpManualLeg.ONCHAIN),
                        )
                    },
                    items.any { item ->
                        lpActionBarBusy(
                            AutoCaptureLpStateRegistry.getState(item.id).offchain,
                            hasArtifactForManualLeg(storage, item.id, LpManualLeg.OFFCHAIN),
                        )
                    },
                )
            }
            filebaseInSlot = inSlot
            onchainBusy = onchain
            offchainBusy = offchain
        }
        refreshBusy()
        launch {
            ProofSetUploader.occupancyUpdates.collect { hash ->
                if (items.any { it.id == hash }) refreshBusy()
            }
        }
        launch {
            AutoCaptureLpStateRegistry.updates.collect { hash ->
                if (items.any { it.id == hash }) refreshBusy()
            }
        }
    }
    val filebaseBusy = filebaseActionBarBusy(inSlot = filebaseInSlot, issuing = filebaseIssuing)
    val releaseFilebaseInFlight = {
        filebaseInFlight.set(false)
        scope.launch(Dispatchers.Main.immediate) { filebaseIssuing = false }
        Unit
    }
    val onFilebaseClick = onFilebaseClick@{
        val config = FilebaseConfig.fromPrefs(PreferenceManager.getDefaultSharedPreferences(context))
        filebaseTapEffectForConfig(config)?.let { effect ->
            applyFilebaseTapEffect(context, effect, releaseFilebaseInFlight)
            return@onFilebaseClick
        }
        if (!filebaseInFlight.compareAndSet(false, true)) return@onFilebaseClick
        filebaseIssuing = true
        scope.launch {
            var releaseInFinally = true
            try {
                val appCtx = context.applicationContext
                val primary = DefaultStorageProvider(appCtx)
                val outcome = withContext(Dispatchers.IO) {
                    val isPinned = { hash: String ->
                        FilebaseSidecarContract.hasSuccessSidecar(primary, hash)
                    }
                    val remaining = FilebaseOnDemandPreflight.filterGeneratedUnpinned(items, isPinned)
                    if (remaining.isEmpty()) {
                        FilebaseOnDemandPreflight.emptyQueueOutcome(items, isPinned)
                    } else {
                        val measured = remaining.map { item ->
                            val mediaLength =
                                ProofSetMediaSource.fromUri(appCtx, item.uri, null).resolve()?.length
                            FilebaseOnDemandCandidate(item, mediaLength)
                        }
                        FilebaseOnDemandPreflight.classify(measured)
                    }
                }
                val effect = filebaseTapEffectForPreflight(outcome)
                if (effect is FilebaseActionBarTapEffect.ShowOversizeConsent ||
                    effect is FilebaseActionBarTapEffect.EnqueueBatch
                ) {
                    releaseInFinally = false
                }
                withContext(Dispatchers.Main) {
                    applyFilebaseTapEffect(context, effect, releaseFilebaseInFlight)
                }
            } finally {
                if (releaseInFinally) releaseFilebaseInFlight()
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(10.dp)) {
        IconButton(onClick = { onShare(items) }) {
            Icon(imageVector = ImageVector.vectorResource(org.witness.proofmode.camera.R.drawable.ic_share), contentDescription = "Share")
        }
        IconButton(onClick = onFilebaseClick) {
            if (filebaseBusy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_filebase_cloud_upload), contentDescription = "Filebase upload")
            }
        }
        if (lpActionIconsVisible(lpActive)) {
            IconButton(onClick = { enqueueLpManualAttest(context, items, LpManualLeg.ONCHAIN) }) {
                if (onchainBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_onchain_signature), contentDescription = "On-chain signature", tint = Color.Unspecified,)
                }
            }
            IconButton(onClick = { enqueueLpManualAttest(context, items, LpManualLeg.OFFCHAIN) }) {
                if (offchainBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_offchain_signature), contentDescription = "Off-chain signature", tint = Color.Unspecified,)
                }
            }
        }
        if (showCancel) {
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onCancel) {
                Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_close_black_24dp), tint = Color.White, contentDescription = "Cancel")
            }
        }
    }
}
