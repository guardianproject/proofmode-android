package org.witness.proofmode.filebase

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.share.formatFilebaseFailureMessage
import org.witness.proofmode.share.isFilebaseReconfigureFailure

class FilebaseOnDemandUiEventsTest {

    @Test
    fun listener_mapsAccountProblemWithMessageToReconfigure_classifiesOnRaw() = runTest {
        FilebaseOnDemandUiEvents.resetForTests()
        val collected = mutableListOf<FilebaseOnDemandUiEvent>()
        val job = launch { FilebaseOnDemandUiEvents.events.collect { collected.add(it) } }
        testScheduler.runCurrent()
        val samePolicy = "ACCOUNT_FALLBACK_SAME_POLICY"
        val listener = FilebaseOnDemandCoordinator.failureListener(
            format = { formatFilebaseFailureMessage(it, samePolicy, samePolicy) },
            isReconfigure = { isFilebaseReconfigureFailure(it) },
        )
        listener.saveFailed(
            Exception(
                "Upload failed: 403 Forbidden key=abc/hash.mp4 contentLength=35424055 " +
                    "body=<Error><Code>AccountProblem</Code>" +
                    "<Message>Free accounts are limited to 25MB video uploads. Please upgrade your plan.</Message>" +
                    "</Error>",
            ),
        )
        testScheduler.advanceUntilIdle()
        job.cancel()
        val event = collected.single() as FilebaseOnDemandUiEvent.Reconfigure
        assertFalse(event.message.contains("AccountProblem"))
    }

    @Test
    fun listener_mapsOtherFailureToDismissible_noRetry() = runTest {
        FilebaseOnDemandUiEvents.resetForTests()
        val collected = mutableListOf<FilebaseOnDemandUiEvent>()
        val job = launch { FilebaseOnDemandUiEvents.events.collect { collected.add(it) } }
        testScheduler.runCurrent()
        val listener = FilebaseOnDemandCoordinator.failureListener(
            format = { raw -> raw ?: "fallback" },
            isReconfigure = { false },
        )
        listener.saveFailed(Exception("socket timeout"))
        testScheduler.advanceUntilIdle()
        job.cancel()
        val event = collected.single() as FilebaseOnDemandUiEvent.DismissibleFailure
        assertTrue(!event.message.contains("Retry", ignoreCase = true))
    }

    @Test
    fun failureWithNoHostListening_isDeliveredToTheNextCollector() = runTest {
        FilebaseOnDemandUiEvents.resetForTests()
        val listener = FilebaseOnDemandCoordinator.failureListener(
            format = { raw -> raw ?: "" },
            isReconfigure = { false },
        )

        // No collector yet: MainActivity is not STARTED.
        listener.saveFailed(Exception("socket timeout"))
        testScheduler.advanceUntilIdle()

        val collected = mutableListOf<FilebaseOnDemandUiEvent>()
        val job = launch { FilebaseOnDemandUiEvents.events.collect { collected.add(it) } }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertTrue(collected.single() is FilebaseOnDemandUiEvent.DismissibleFailure)
    }

    @Test
    fun collectorRestart_doesNotReplayAlreadyShownEvents() = runTest {
        FilebaseOnDemandUiEvents.resetForTests()
        val listener = FilebaseOnDemandCoordinator.failureListener(
            format = { raw -> raw ?: "" },
            isReconfigure = { false },
        )
        listener.saveFailed(Exception("socket timeout"))

        val first = mutableListOf<FilebaseOnDemandUiEvent>()
        val firstJob = launch { FilebaseOnDemandUiEvents.events.collect { first.add(it) } }
        testScheduler.advanceUntilIdle()
        firstJob.cancel()

        val second = mutableListOf<FilebaseOnDemandUiEvent>()
        val secondJob = launch { FilebaseOnDemandUiEvents.events.collect { second.add(it) } }
        testScheduler.advanceUntilIdle()
        secondJob.cancel()

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }
}
