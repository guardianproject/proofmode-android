package org.witness.proofmode.filebase

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.proofset.MediaInclusion
import org.witness.proofmode.ui.ProofStatus
import org.witness.proofmode.ui.ProofableItem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class FilebaseOnDemandPreflightTest {
    private fun item(id: String, status: ProofStatus = ProofStatus.GENERATED) =
        ProofableItem(id = id, uri = Uri.parse("content://media/$id"), proofStatus = status)

    @Test
    fun filter_skipsNonGeneratedAndBlankHashAndPinned() {
        val items = listOf(
            item(""),
            item("pending", ProofStatus.PENDING),
            item("generating", ProofStatus.GENERATING),
            item("pinned"),
            item("ok"),
        )
        val kept = FilebaseOnDemandPreflight.filterGeneratedUnpinned(items) { hash ->
            hash == "pinned"
        }
        assertEquals(listOf("ok"), kept.map { it.id })
    }

    @Test
    fun emptyQueue_allAlreadyPinned_isAlreadyPinned() {
        val items = listOf(item("pinned-a"), item("pinned-b"))
        assertEquals(
            FilebasePreflightOutcome.AlreadyPinned,
            FilebaseOnDemandPreflight.emptyQueueOutcome(items) { true },
        )
    }

    @Test
    fun emptyQueue_mixPinnedAndPending_isNotReady() {
        val items = listOf(item("pinned"), item("pending", ProofStatus.PENDING))
        assertEquals(
            FilebasePreflightOutcome.EmptyAfterFilters,
            FilebaseOnDemandPreflight.emptyQueueOutcome(items) { it == "pinned" },
        )
    }

    @Test
    fun classify_allWithinLimit_readyIncludeMedia() {
        val c = FilebaseOnDemandCandidate(item("a"), FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES)
        val outcome = FilebaseOnDemandPreflight.classify(listOf(c))
        val ready = outcome as FilebasePreflightOutcome.Ready
        assertEquals(MediaInclusion.INCLUDE_MEDIA, ready.queue.single().mediaInclusion)
    }

    @Test
    fun classify_anyOversize_needsConsent() {
        val within = FilebaseOnDemandCandidate(item("a"), 1L)
        val over = FilebaseOnDemandCandidate(
            item("b"),
            FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L,
        )
        val outcome = FilebaseOnDemandPreflight.classify(listOf(within, over))
            as FilebasePreflightOutcome.NeedsOversizeConsent
        assertEquals(1, outcome.oversizeCount)
        assertEquals(2, outcome.remaining.size)
    }

    @Test
    fun classify_unresolvedLength_isNotOversizeConsent() {
        val unknown = FilebaseOnDemandCandidate(item("c"), null)
        val zero = FilebaseOnDemandCandidate(item("z"), 0L)
        assertEquals(
            FilebasePreflightOutcome.EmptyAfterFilters,
            FilebaseOnDemandPreflight.classify(listOf(unknown, zero)),
        )
        val within = FilebaseOnDemandCandidate(item("a"), 1L)
        val ready = FilebaseOnDemandPreflight.classify(listOf(within, unknown))
            as FilebasePreflightOutcome.Ready
        assertEquals(listOf("a"), ready.queue.map { it.hash })
        val over = FilebaseOnDemandCandidate(
            item("b"),
            FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L,
        )
        val consent = FilebaseOnDemandPreflight.classify(listOf(over, unknown))
            as FilebasePreflightOutcome.NeedsOversizeConsent
        assertEquals(1, consent.oversizeCount)
        assertEquals(listOf("b"), consent.remaining.map { it.item.id })
    }

    @Test
    fun confirm_mapsOversizeToSidecarsOnly() {
        val within = FilebaseOnDemandCandidate(item("a"), 1L)
        val over = FilebaseOnDemandCandidate(
            item("b"),
            FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L,
        )
        val queue = FilebaseOnDemandPreflight.queueAfterOversizeConfirm(listOf(within, over))
        assertEquals(MediaInclusion.INCLUDE_MEDIA, queue[0].mediaInclusion)
        assertEquals(MediaInclusion.SIDECARS_ONLY, queue[1].mediaInclusion)
    }

    @Test
    fun onDemandMimeType_fallsBackToOctetStream() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://proofmode.test/unknown")
        assertEquals("application/octet-stream", onDemandMimeType(context, uri))
    }
}
