package org.witness.proofmode.filebase

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

sealed class FilebaseOnDemandUiEvent {
    data class Reconfigure(val message: String) : FilebaseOnDemandUiEvent()
    data class DismissibleFailure(val message: String) : FilebaseOnDemandUiEvent()
}

/**
 * Process-wide queue of on-demand Filebase outcomes waiting for a UI host.
 *
 * Buffered, not broadcast: an app-scoped upload routinely fails while no Activity is STARTED,
 * and that outcome must still reach the next host that resumes. Each event is handed to exactly
 * one collector, so a lifecycle-gated collector drains the backlog instead of missing it.
 */
object FilebaseOnDemandUiEvents {
    private val queue = Channel<FilebaseOnDemandUiEvent>(Channel.UNLIMITED)

    val events: Flow<FilebaseOnDemandUiEvent> = queue.receiveAsFlow()

    fun tryEmit(event: FilebaseOnDemandUiEvent) = queue.trySend(event).isSuccess

    /** Drains pending events. Never swaps the channel — live collectors keep their subscription. */
    fun resetForTests() {
        while (queue.tryReceive().isSuccess) {
            // discard
        }
    }
}
