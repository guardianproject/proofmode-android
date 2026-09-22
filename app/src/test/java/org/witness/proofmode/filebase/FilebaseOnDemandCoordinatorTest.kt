package org.witness.proofmode.filebase

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.net.FakeUri
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.proofset.MediaInclusion

/**
 * On-demand issue helper tests (no tapped-set occupancy sequencer).
 *
 * Drives [FilebaseOnDemandCoordinator.issueOnDemandFlush] inside [runTest] so
 * [testScheduler] observes the issue loop. Avoids Robolectric and mockito: on
 * JDK 25, Robolectric 4.13's ASM cannot instrument `android.net.Uri.parse`.
 * Injected [flushOne] never reads [FilebaseOnDemandItem.uri], so tests use
 * [FakeUri].
 */
class FilebaseOnDemandCoordinatorTest {
    private fun item(hash: String) = FilebaseOnDemandItem(
        hash, FakeUri(), MediaInclusion.INCLUDE_MEDIA,
    )

    @Test
    fun issueOnDemandFlush_issuesEveryItemInOrderWithoutWaitingOnTheOneBefore() = runTest {
        val started = mutableListOf<String>()
        FilebaseOnDemandCoordinator.issueOnDemandFlush(
            scope = this,
            appContext = object : ContextWrapper(null) {},
            items = listOf(item("a"), item("b")),
            listenerFactory = { null },
            flushOne = { it, _ ->
                started.add(it.hash)
            },
        )
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a", "b"), started)
    }

    @Test
    fun issueOnDemandFlush_defaultListenerFactory_mapsSaveFailedToUiEvents() = runTest {
        FilebaseOnDemandUiEvents.resetForTests()
        val collected = mutableListOf<FilebaseOnDemandUiEvent>()
        val collectJob = launch { FilebaseOnDemandUiEvents.events.collect { collected.add(it) } }
        var captured: StorageListener? = null
        val strings = @Suppress("DEPRECATION") object : Resources(null, null, null) {
            override fun getString(id: Int): String = "same-policy"
        }
        val appContext: Context = object : ContextWrapper(null) {
            override fun getResources(): Resources = strings
        }
        FilebaseOnDemandCoordinator.issueOnDemandFlush(
            scope = this,
            appContext = appContext,
            items = listOf(FilebaseOnDemandItem("h", FakeUri(), MediaInclusion.INCLUDE_MEDIA)),
            flushOne = { _, listener ->
                captured = listener
            },
            // omit listenerFactory — production default must post DismissibleFailure
        )
        testScheduler.advanceUntilIdle()
        org.junit.Assert.assertNotNull(captured)
        captured!!.saveFailed(Exception("socket timeout"))
        testScheduler.advanceUntilIdle()
        collectJob.cancel()
        assertTrue(collected.single() is FilebaseOnDemandUiEvent.DismissibleFailure)
    }
}
