package org.witness.proofmode.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.ProofModeApp
import org.witness.proofmode.R
import org.witness.proofmode.filebase.FilebaseOnDemandCandidate
import org.witness.proofmode.filebase.FilebaseOnDemandCoordinator
import org.witness.proofmode.filebase.FilebaseOnDemandItem
import org.witness.proofmode.filebase.FilebaseOnDemandPreflight
import org.witness.proofmode.filebase.FilebasePreflightOutcome
import org.witness.proofmode.lp.LpManualLeg
import org.witness.proofmode.lp.enqueueManualAttestForShareProof
import org.witness.proofmode.lp.hasArtifactForManualLeg
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.wallet.WalletSettingsActivity
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

fun lpGeneratedItemsForManualEnqueue(items: List<ProofableItem>): List<ProofableItem> =
    items.filter { it.proofStatus == ProofStatus.GENERATED && it.uri != Uri.EMPTY }

@StringRes
internal fun lpTapToastRes(leg: LpManualLeg, succeeded: Int, total: Int): Int = when {
    succeeded == total -> when (leg) {
        LpManualLeg.OFFCHAIN -> R.string.lp_toast_offchain_success
        LpManualLeg.ONCHAIN -> R.string.lp_toast_onchain_success
    }
    succeeded == 0 -> when (leg) {
        LpManualLeg.OFFCHAIN -> R.string.lp_toast_offchain_failed
        LpManualLeg.ONCHAIN -> R.string.lp_toast_onchain_failed
    }
    else -> R.string.proofset_toast_mixed
}

/** Upload shows no failure toast: a none-ok tap leaves the Filebase failure dialogs as its only surface. */
@StringRes
internal fun uploadTapToastRes(succeeded: Int, total: Int): Int? = when {
    succeeded == 0 -> null
    succeeded == total -> R.string.filebase_upload_success
    else -> R.string.proofset_toast_mixed
}

/**
 * Waits for the [issued] items' reports, then posts the tap's one toast through [toast].
 * Already-done items count ok up front, so an all-pinned tap posts at once. An item whose
 * flush [org.witness.proofmode.storage.CompositeStorageProvider] drops without a callback
 * never reports, so that tap posts nothing.
 */
internal suspend fun awaitUploadTap(
    outcomes: ReceiveChannel<Boolean>,
    issued: Int,
    alreadyDone: Int,
    toast: (res: Int, succeeded: Int, total: Int) -> Unit,
) {
    var succeeded = alreadyDone
    repeat(issued) { if (outcomes.receive()) succeeded++ }
    val total = alreadyDone + issued
    uploadTapToastRes(succeeded, total)?.let { toast(it, succeeded, total) }
}

private fun enqueueLpManualAttest(context: Context, items: List<ProofableItem>, leg: LpManualLeg) {
    // Ahead of the nothing-to-queue toast, as the Filebase config dialog precedes its preflight toast.
    if (!LocationProtocolPlugin.walletDiagnostics().connected) {
        showWalletRequiredDialog(context)
        return
    }
    val generated = lpGeneratedItemsForManualEnqueue(items)
    if (generated.isEmpty()) {
        Toast.makeText(context, R.string.lp_attest_nothing_to_queue, Toast.LENGTH_LONG).show()
        return
    }
    // The work outlives the screen, so the toast closure keeps the application, never `context`.
    val app = context.applicationContext as ProofModeApp
    // A GENERATED item's id is its proof hash (Activities.markProofGenerated).
    LocationProtocolPlugin.requireApplicationScope().launch {
        enqueueManualAttestForShareProof(
            appContext = app,
            items = generated.map { it.uri to it.id },
            leg = leg,
            storage = DefaultStorageProvider(app),
            onTapDone = { succeeded, total ->
                app.showToastMessage(app.getString(lpTapToastRes(leg, succeeded, total), succeeded, total))
            },
        )
    }
}

private fun showWalletRequiredDialog(context: Context) {
    AlertDialog.Builder(context)
        .setTitle(R.string.wallet_required_dialog_title)
        .setMessage(R.string.wallet_required_dialog_message_manual)
        .setPositiveButton(R.string.wallet_required_dialog_connect) { _, _ ->
            context.startActivity(Intent(context, WalletSettingsActivity::class.java))
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
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

private fun enqueueFilebaseBatch(
    context: Context,
    queue: List<FilebaseOnDemandItem>,
    alreadyDone: List<String>,
): Job {
    val app = context.applicationContext as ProofModeApp
    val scope = app.applicationCoroutineScope()
    // Unlimited so a report on the uploader's IO thread never blocks; see issueOnDemandFlush.
    val outcomes = Channel<Boolean>(Channel.UNLIMITED)
    scope.launch {
        awaitUploadTap(outcomes, issued = queue.size, alreadyDone = alreadyDone.size) { res, succeeded, total ->
            app.showToastMessage(app.getString(res, succeeded, total))
        }
    }
    return FilebaseOnDemandCoordinator.issueOnDemandFlush(
        scope = scope,
        appContext = app.applicationContext,
        items = queue,
        onItemDone = { outcomes.trySend(it) },
    )
}

internal fun showFilebaseOversizeConsentDialog(
    context: Context,
    oversizeCount: Int,
    confirmQueue: List<FilebaseOnDemandItem>,
    alreadyDone: List<String>,
    releaseIssuing: () -> Unit,
) {
    AlertDialog.Builder(context)
        .setTitle(R.string.filebase_upload_oversize_title)
        .setMessage(context.getString(R.string.filebase_upload_oversize_batch_message, oversizeCount))
        .setPositiveButton(R.string.filebase_upload_oversize_confirm) { _, _ ->
            enqueueFilebaseBatch(context, confirmQueue, alreadyDone)
                .invokeOnCompletion { releaseIssuing() }
        }
        .setNegativeButton(R.string.filebase_upload_oversize_cancel) { _, _ -> releaseIssuing() }
        .setCancelable(true)
        .setOnCancelListener { releaseIssuing() }
        .show()
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
    val releaseFilebaseIssuing = {
        scope.launch(Dispatchers.Main.immediate) { filebaseIssuing = false }
        Unit
    }
    val onFilebaseClick = onFilebaseClick@{
        val config = FilebaseConfig.fromPrefs(PreferenceManager.getDefaultSharedPreferences(context))
        if (!config.isConfigured()) {
            showFilebaseNotConfiguredDialog(context)
            return@onFilebaseClick
        }
        if (filebaseIssuing) return@onFilebaseClick
        filebaseIssuing = true
        scope.launch {
            var releaseInFinally = true
            try {
                val appCtx = context.applicationContext
                val primary = DefaultStorageProvider(appCtx)
                val (outcome, alreadyDone) = withContext(Dispatchers.IO) {
                    val isPinned = { hash: String ->
                        FilebaseSidecarContract.hasSuccessSidecar(primary, hash)
                    }
                    val pins = FilebaseOnDemandPreflight.partitionByPin(items, isPinned)
                    val outcome = if (pins.unpinned.isEmpty()) {
                        FilebaseOnDemandPreflight.emptyQueueOutcome(items, pins.pinnedHashes)
                    } else {
                        val measured = pins.unpinned.map { item ->
                            val mediaLength =
                                ProofSetMediaSource.fromUri(appCtx, item.uri, null).resolve()?.length
                            FilebaseOnDemandCandidate(item, mediaLength)
                        }
                        FilebaseOnDemandPreflight.classify(measured)
                    }
                    outcome to pins.pinnedHashes
                }
                if (outcome !is FilebasePreflightOutcome.EmptyAfterFilters) releaseInFinally = false
                withContext(Dispatchers.Main) {
                    when (outcome) {
                        FilebasePreflightOutcome.EmptyAfterFilters ->
                            Toast.makeText(context, R.string.filebase_proof_not_ready, Toast.LENGTH_LONG).show()
                        is FilebasePreflightOutcome.Ready ->
                            enqueueFilebaseBatch(context, outcome.queue, alreadyDone)
                                .invokeOnCompletion { releaseFilebaseIssuing() }
                        is FilebasePreflightOutcome.NeedsOversizeConsent ->
                            showFilebaseOversizeConsentDialog(
                                context,
                                outcome.oversizeCount,
                                FilebaseOnDemandPreflight.queueAfterOversizeConfirm(outcome.remaining),
                                alreadyDone,
                                releaseFilebaseIssuing,
                            )
                    }
                }
            } finally {
                if (releaseInFinally) releaseFilebaseIssuing()
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
        if (lpActive) {
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
