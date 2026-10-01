package org.witness.proofmode.ui

import android.net.FakeUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.filebase.FilebaseOnDemandCandidate
import org.witness.proofmode.filebase.FilebaseOnDemandItem
import org.witness.proofmode.filebase.FilebaseOnDemandPreflight
import org.witness.proofmode.filebase.FilebasePreflightOutcome
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.proofset.MediaInclusion

class ProofsetActionBarFilebaseEffectTest {
    private fun item(id: String) = ProofableItem(id, FakeUri(), ProofStatus.GENERATED)

    @Test
    fun configEffect_notConfigured_showsDialog() {
        val config = FilebaseConfig("", "", "", enabled = false)
        assertEquals(
            FilebaseActionBarTapEffect.ShowNotConfiguredDialog,
            filebaseTapEffectForConfig(config),
        )
    }

    @Test
    fun configEffect_uploadModeNone_showsDialog() {
        val config = FilebaseConfig("", "", "", enabled = true)
        assertEquals(
            FilebaseActionBarTapEffect.ShowNotConfiguredDialog,
            filebaseTapEffectForConfig(config),
        )
    }

    @Test
    fun configEffect_ready_returnsNullToContinuePreflight() {
        val config = FilebaseConfig("a", "s", "b", enabled = true)
        assertNull(filebaseTapEffectForConfig(config))
    }

    @Test
    fun preflightEffect_emptyAfterFilters_showsToast() {
        assertEquals(
            FilebaseActionBarTapEffect.ShowProofNotReadyToast,
            filebaseTapEffectForPreflight(FilebasePreflightOutcome.EmptyAfterFilters),
        )
    }

    @Test
    fun preflightEffect_alreadyPinned_isNoOp() {
        assertEquals(
            FilebaseActionBarTapEffect.NoOp,
            filebaseTapEffectForPreflight(FilebasePreflightOutcome.AlreadyPinned),
        )
    }

    @Test
    fun emptyQueue_allAlreadyPinned_mapsToNoOp() {
        val items = listOf(item("pinned-a"), item("pinned-b"))
        val outcome = FilebaseOnDemandPreflight.emptyQueueOutcome(items) { true }
        assertEquals(FilebasePreflightOutcome.AlreadyPinned, outcome)
        assertEquals(FilebaseActionBarTapEffect.NoOp, filebaseTapEffectForPreflight(outcome))
    }

    @Test
    fun emptyQueue_notGenerated_mapsToNotReadyToast() {
        val items = listOf(ProofableItem("pending", FakeUri(), ProofStatus.PENDING))
        val outcome = FilebaseOnDemandPreflight.emptyQueueOutcome(items) { false }
        assertEquals(FilebasePreflightOutcome.EmptyAfterFilters, outcome)
        assertEquals(
            FilebaseActionBarTapEffect.ShowProofNotReadyToast,
            filebaseTapEffectForPreflight(outcome),
        )
    }

    @Test
    fun preflightEffect_ready_enqueuesBatch() {
        val queue = listOf(
            FilebaseOnDemandItem("a", FakeUri(), MediaInclusion.INCLUDE_MEDIA),
        )
        val effect = filebaseTapEffectForPreflight(FilebasePreflightOutcome.Ready(queue))
        assertEquals(FilebaseActionBarTapEffect.EnqueueBatch(queue), effect)
    }

    @Test
    fun preflightEffect_oversize_showsConsentWithConfirmQueue() {
        val within = FilebaseOnDemandCandidate(item("a"), 1L)
        val over = FilebaseOnDemandCandidate(
            item("b"),
            FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L,
        )
        val outcome = FilebasePreflightOutcome.NeedsOversizeConsent(listOf(within, over), 1)
        val effect = filebaseTapEffectForPreflight(outcome) as FilebaseActionBarTapEffect.ShowOversizeConsent
        assertEquals(1, effect.oversizeCount)
        assertEquals(
            FilebaseOnDemandPreflight.queueAfterOversizeConfirm(outcome.remaining),
            effect.confirmQueue,
        )
    }

    @Test
    fun oversizeConsent_carriesAConfirmQueueThatDropsOnlyTheOversizeMedia() {
        val within = FilebaseOnDemandCandidate(item("a"), 1L)
        val over = FilebaseOnDemandCandidate(
            item("b"),
            FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L,
        )
        val outcome = FilebasePreflightOutcome.NeedsOversizeConsent(listOf(within, over), 1)

        val effect = filebaseTapEffectForPreflight(outcome)

        assertTrue(effect is FilebaseActionBarTapEffect.ShowOversizeConsent)
        val consent = effect as FilebaseActionBarTapEffect.ShowOversizeConsent
        assertEquals(1, consent.oversizeCount)
        assertEquals(MediaInclusion.INCLUDE_MEDIA, consent.confirmQueue[0].mediaInclusion)
        assertEquals(MediaInclusion.SIDECARS_ONLY, consent.confirmQueue[1].mediaInclusion)
    }
}
