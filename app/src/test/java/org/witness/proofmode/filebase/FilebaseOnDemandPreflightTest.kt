package org.witness.proofmode.filebase

import android.net.Uri
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
    fun partitionByPin_skipsNonGeneratedAndBlankHash_andSplitsPinned() {
        val items = listOf(
            item(""),
            item("pending", ProofStatus.PENDING),
            item("pending-pinned", ProofStatus.PENDING),
            item("generating", ProofStatus.GENERATING),
            item("pinned"),
            item("ok"),
        )
        val probed = mutableListOf<String>()
        val pins = FilebaseOnDemandPreflight.partitionByPin(items) { hash ->
            probed += hash
            hash == "pinned" || hash == "pending-pinned" || hash == ""
        }
        assertEquals(listOf("ok"), pins.unpinned.map { it.id })
        assertEquals(listOf("pinned"), pins.pinnedHashes)
        // Each eligible item costs one sidecar read.
        assertEquals(listOf("pinned", "ok"), probed)
    }

    @Test
    fun emptyQueue_allPinned_isReadyEmpty_mixIsEmptyAfterFilters() {
        val allPinned = listOf(item("pinned-a"), item("pinned-b"))
        assertEquals(
            FilebasePreflightOutcome.Ready(emptyList()),
            FilebaseOnDemandPreflight.emptyQueueOutcome(allPinned, pinnedHashes = listOf("pinned-a", "pinned-b")),
        )
        val mix = listOf(item("pinned"), item("pending", ProofStatus.PENDING))
        assertEquals(
            FilebasePreflightOutcome.EmptyAfterFilters,
            FilebaseOnDemandPreflight.emptyQueueOutcome(mix, pinnedHashes = listOf("pinned")),
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
        val consent = FilebaseOnDemandPreflight.classify(listOf(within, over, unknown))
            as FilebasePreflightOutcome.NeedsOversizeConsent
        assertEquals(1, consent.oversizeCount)
        assertEquals(listOf("a", "b"), consent.remaining.map { it.item.id })
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
}
