package org.witness.proofmode.filebase

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.witness.proofmode.R
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.share.formatFilebaseFailureMessage
import org.witness.proofmode.share.isFilebaseReconfigureFailure
import org.witness.proofmode.storage.CompositeStorageProvider
import org.witness.proofmode.storage.StorageListener

/**
 * Application-scoped on-demand Filebase issue helper.
 *
 * After bar preflight, [issueOnDemandFlush] marks remaining hashes and MIME
 * [bindMedia]s on the process Composite without waiting occupancy of hash n
 * before n+1. Not a dialog host, not a membership engine, not a tapped-set
 * sequencer. Production defaults [listenerFactory] to [failureListener], which
 * posts [FilebaseOnDemandUiEvent]s for a surviving UI host. Reuse the caller's
 * [scope] (production: [org.witness.proofmode.ProofModeApp.applicationCoroutineScope]).
 * Do not mint a Filebase SupervisorJob. Do not hop to [Dispatchers.IO] here.
 */
object FilebaseOnDemandCoordinator {

    fun issueOnDemandFlush(
        scope: CoroutineScope,
        appContext: Context,
        items: List<FilebaseOnDemandItem>,
        listenerFactory: (hash: String) -> StorageListener? = { failureListener(appContext) },
        flushOne: (FilebaseOnDemandItem, StorageListener?) -> Unit =
            { item, listener -> defaultOnDemandFlush(appContext, item, listener) },
    ): Job = scope.launch {
        for (item in items) {
            flushOne(item, listenerFactory(item.hash))
        }
    }

    fun failureListener(
        format: (String?) -> String,
        isReconfigure: (String?) -> Boolean = { isFilebaseReconfigureFailure(it) },
    ): StorageListener = object : StorageListener {
        override fun saveSuccessful(hash: String?, uri: String?) {}
        override fun saveFailed(exception: Exception?) {
            val raw = exception?.message
            val message = format(raw)
            FilebaseOnDemandUiEvents.tryEmit(
                if (isReconfigure(raw)) FilebaseOnDemandUiEvent.Reconfigure(message)
                else FilebaseOnDemandUiEvent.DismissibleFailure(message),
            )
        }
    }

    fun failureListener(appContext: Context): StorageListener {
        val samePolicy = appContext.resources.getString(R.string.filebase_failure_same_policy)
        return failureListener(
            format = { formatFilebaseFailureMessage(it, samePolicy, samePolicy) },
            isReconfigure = { isFilebaseReconfigureFailure(it) },
        )
    }
}

internal fun defaultOnDemandFlush(
    appContext: Context,
    item: FilebaseOnDemandItem,
    listener: StorageListener?,
) {
    val watcher = MediaWatcher.getInstance(appContext) ?: return
    val composite = watcher.storageProvider as? CompositeStorageProvider ?: return
    composite.markOnDemand(item.hash, item.mediaInclusion, listener)
    composite.bindMedia(item.hash, item.uri, onDemandMimeType(appContext, item.uri))
    // do NOT call requestFlush — bindMedia already tryFlushes when shouldAutomaticFlush
}

internal fun onDemandMimeType(context: Context, uri: Uri): String =
    context.contentResolver.getType(uri) ?: "application/octet-stream"
