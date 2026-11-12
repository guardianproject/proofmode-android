package org.witness.proofmode.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.R
import org.witness.proofmode.filebase.FilebaseOnDemandCoordinator
import org.witness.proofmode.filebase.FilebaseOnDemandItem
import org.witness.proofmode.filebase.FilebaseOnDemandUiEvent
import org.witness.proofmode.filebase.FilebaseOnDemandUiEvents
import org.witness.proofmode.filebase.collectUiEvents
import org.witness.proofmode.filebase.eagerScope
import org.witness.proofmode.filebase.fakeAppContext
import org.witness.proofmode.filebase.onDemandItem
import org.witness.proofmode.storage.StorageListener

/**
 * The Upload tap's counting: the real [FilebaseOnDemandCoordinator.issueOnDemandFlush] loop and
 * default listener feed [awaitUploadTap]. Plain JUnit with a fake uri and a hand-written
 * `Context`, for the reason given in `FilebaseOnDemandCoordinatorTest`.
 */
class ProofsetActionBarUploadTapTest {
    private data class Posted(val res: Int, val succeeded: Int, val total: Int)

    private class UploadTap(val posted: MutableList<Posted>, val listeners: MutableMap<String, StorageListener?>)

    private enum class Outcome { OK, FAIL }

    private class Row(
        val name: String,
        val hashes: List<String>,
        val alreadyDone: Int,
        val outcomes: List<Outcome>,
        val expected: List<Posted>,
        val dialogs: Int,
    )

    private fun TestScope.uploadTap(items: List<FilebaseOnDemandItem>, alreadyDone: Int): UploadTap {
        val tap = UploadTap(mutableListOf(), mutableMapOf())
        val outcomes = Channel<Boolean>(Channel.UNLIMITED)
        val scope = eagerScope()
        scope.launch {
            awaitUploadTap(outcomes, issued = items.size, alreadyDone = alreadyDone) { res, succeeded, total ->
                tap.posted += Posted(res, succeeded, total)
            }
        }
        FilebaseOnDemandCoordinator.issueOnDemandFlush(
            scope = scope,
            appContext = fakeAppContext(),
            items = items,
            onItemDone = { outcomes.trySend(it) },
            flushOne = { it, listener -> tap.listeners[it.hash] = listener },
        )
        return tap
    }

    @Test
    fun awaitUploadTap_table() = runTest {
        val success = R.string.filebase_upload_success
        val mixed = R.string.proofset_toast_mixed
        val rows = listOf(
            Row(name = "allOk", hashes = listOf("a", "b"), alreadyDone = 0, outcomes = listOf(Outcome.OK, Outcome.OK), expected = listOf(Posted(success, 2, 2)), dialogs = 0),
            Row(name = "someOk", hashes = listOf("a", "b"), alreadyDone = 0, outcomes = listOf(Outcome.OK, Outcome.FAIL), expected = listOf(Posted(mixed, 1, 2)), dialogs = 1),
            Row(name = "noneOk", hashes = listOf("a", "b"), alreadyDone = 0, outcomes = listOf(Outcome.FAIL, Outcome.FAIL), expected = emptyList(), dialogs = 2),
            Row(name = "alreadyDoneAndFail", hashes = listOf("b"), alreadyDone = 1, outcomes = listOf(Outcome.FAIL), expected = listOf(Posted(mixed, 1, 2)), dialogs = 1),
            Row(name = "allPinned", hashes = emptyList(), alreadyDone = 2, outcomes = emptyList(), expected = listOf(Posted(success, 2, 2)), dialogs = 0),
            Row(name = "zeroTotal", hashes = emptyList(), alreadyDone = 0, outcomes = emptyList(), expected = emptyList(), dialogs = 0),
        )
        // One collector for all rows: the queue hands each event to a single collector.
        val collected = mutableListOf<FilebaseOnDemandUiEvent>()
        FilebaseOnDemandUiEvents.resetForTests()
        collectUiEvents(collected)
        for (row in rows) {
            collected.clear()
            val tap = uploadTap(row.hashes.map { onDemandItem(it) }, row.alreadyDone)

            row.hashes.zip(row.outcomes).forEachIndexed { index, (hash, outcome) ->
                val listener = tap.listeners.getValue(hash)!!
                when (outcome) {
                    Outcome.OK -> listener.saveSuccessful(hash, "u")
                    Outcome.FAIL -> listener.saveFailed(Exception("fail $hash"))
                }
                // No toast until the last report.
                if (index < row.hashes.size - 1) assertEquals(row.name, emptyList<Posted>(), tap.posted)
            }

            assertEquals(row.name, row.expected, tap.posted)
            assertEquals(row.name, row.dialogs, collected.size)
            assertTrue(row.name, collected.all { it is FilebaseOnDemandUiEvent.DismissibleFailure })
        }
    }
}
