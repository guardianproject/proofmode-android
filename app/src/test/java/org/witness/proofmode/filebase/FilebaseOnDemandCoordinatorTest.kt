package org.witness.proofmode.filebase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.witness.proofmode.storage.StorageListener

/**
 * On-demand issue helper tests (no tapped-set occupancy sequencer).
 *
 * Drives [FilebaseOnDemandCoordinator.issueOnDemandFlush] inside [runTest] so
 * [testScheduler] observes the issue loop. Avoids Robolectric and mockito: on
 * JDK 25, Robolectric 4.13's ASM cannot instrument `android.net.Uri.parse`.
 * Injected [flushOne] never reads [FilebaseOnDemandItem.uri], so tests use a
 * fake `Uri` (see `onDemandItem`).
 */
class FilebaseOnDemandCoordinatorTest {
    @Test
    fun failureListener_duplicateTerminalCallbacks_reportOnce() = runTest {
        class Sequence(
            val calls: (StorageListener) -> Unit,
            val reportedOk: Boolean,
            val dialogs: Int,
        )
        val sequences = listOf(
            Sequence({ l -> l.saveFailed(null); l.saveSuccessful("h", "u") }, reportedOk = false, dialogs = 1),
            Sequence({ l -> l.saveSuccessful("h", "u"); l.saveFailed(null) }, reportedOk = true, dialogs = 1),
            Sequence({ l -> l.saveFailed(null); l.saveFailed(null) }, reportedOk = false, dialogs = 2),
        )
        // One collector for all sequences: the queue hands each event to a single collector.
        val collected = mutableListOf<FilebaseOnDemandUiEvent>()
        collectUiEvents(collected)
        for (sequence in sequences) {
            collected.clear()
            val reports = mutableListOf<Boolean>()
            val listener = FilebaseOnDemandCoordinator.failureListener(
                format = { "msg" },
                onDone = { reports += it },
            )

            sequence.calls(listener)

            assertEquals(listOf(sequence.reportedOk), reports)
            // Every saveFailed still posts its dialog; only the report is once.
            assertEquals(sequence.dialogs, collected.size)
        }
    }
}
