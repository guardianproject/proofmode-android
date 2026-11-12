package org.witness.proofmode.filebase

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.share.formatFilebaseFailureMessage
import org.witness.proofmode.share.isFilebaseReconfigureFailure
import org.witness.proofmode.storage.CompositeStorageProvider
import org.witness.proofmode.storage.StorageListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issues one flush per item on the caller's scope; each item reports once.
 */
object FilebaseOnDemandCoordinator {

    /**
     * [onItemDone] is called once per item with ok or failed. It runs on the uploader's IO
     * thread inside its per-hash lock, so it must not block or suspend.
     */
    fun issueOnDemandFlush(
        scope: CoroutineScope,
        appContext: Context,
        items: List<FilebaseOnDemandItem>,
        onItemDone: (succeeded: Boolean) -> Unit,
        flushOne: (FilebaseOnDemandItem, StorageListener?) -> Unit =
            { item, listener -> defaultOnDemandFlush(appContext, item, listener) },
    ): Job = scope.launch {
        for (item in items) {
            flushOne(item, failureListener(appContext, onItemDone))
        }
    }

    /**
     * Posts the failure dialog on every `saveFailed`, and tells [onDone] the first terminal
     * outcome only. The uploader can call a listener twice, for example when a sidecar save
     * re-enters a flush mid-upload, and the tap must count an item once.
     */
    fun failureListener(
        format: (String?) -> String,
        onDone: (succeeded: Boolean) -> Unit = {},
    ): StorageListener = object : StorageListener {
        private val reported = AtomicBoolean(false)

        override fun saveSuccessful(hash: String?, uri: String?) {
            reportOnce(true)
        }

        override fun saveFailed(exception: Exception?) {
            val raw = exception?.message
            val message = format(raw)
            FilebaseOnDemandUiEvents.tryEmit(
                if (isFilebaseReconfigureFailure(raw)) FilebaseOnDemandUiEvent.Reconfigure(message)
                else FilebaseOnDemandUiEvent.DismissibleFailure(message),
            )
            reportOnce(false)
        }

        private fun reportOnce(succeeded: Boolean) {
            if (reported.compareAndSet(false, true)) onDone(succeeded)
        }
    }

    fun failureListener(appContext: Context, onDone: (succeeded: Boolean) -> Unit): StorageListener =
        failureListener(
            format = { formatFilebaseFailureMessage(appContext, it) },
            onDone = onDone,
        )
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
