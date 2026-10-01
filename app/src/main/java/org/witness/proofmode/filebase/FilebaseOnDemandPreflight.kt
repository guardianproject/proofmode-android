package org.witness.proofmode.filebase

import android.net.Uri
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.proofset.MediaInclusion
import org.witness.proofmode.ui.ProofStatus
import org.witness.proofmode.ui.ProofableItem

data class FilebaseOnDemandCandidate(
    val item: ProofableItem,
    val mediaLength: Long?,
)

data class FilebaseOnDemandItem(
    val hash: String,
    val uri: Uri,
    val mediaInclusion: MediaInclusion,
)

sealed class FilebasePreflightOutcome {
    data object EmptyAfterFilters : FilebasePreflightOutcome()
    data object AlreadyPinned : FilebasePreflightOutcome()
    data class Ready(val queue: List<FilebaseOnDemandItem>) : FilebasePreflightOutcome()
    data class NeedsOversizeConsent(
        val remaining: List<FilebaseOnDemandCandidate>,
        val oversizeCount: Int,
    ) : FilebasePreflightOutcome()
}

object FilebaseOnDemandPreflight {
    fun isUnresolvedLength(mediaLength: Long?): Boolean =
        mediaLength == null || mediaLength <= 0L

    fun isOversize(mediaLength: Long?): Boolean =
        mediaLength != null &&
            !isUnresolvedLength(mediaLength) &&
            !FilebaseConfig.isWithinFilebaseMediaLimit(mediaLength)

    fun classify(
        remainingAfterPins: List<FilebaseOnDemandCandidate>,
    ): FilebasePreflightOutcome {
        if (remainingAfterPins.isEmpty()) return FilebasePreflightOutcome.EmptyAfterFilters
        val measurable = remainingAfterPins.filterNot { isUnresolvedLength(it.mediaLength) }
        if (measurable.isEmpty()) return FilebasePreflightOutcome.EmptyAfterFilters
        val oversizeCount = measurable.count { isOversize(it.mediaLength) }
        if (oversizeCount == 0) {
            return FilebasePreflightOutcome.Ready(
                measurable.map {
                    FilebaseOnDemandItem(it.item.id, it.item.uri, MediaInclusion.INCLUDE_MEDIA)
                },
            )
        }
        return FilebasePreflightOutcome.NeedsOversizeConsent(measurable, oversizeCount)
    }

    fun filterGeneratedUnpinned(
        items: List<ProofableItem>,
        isPinned: (String) -> Boolean,
    ): List<ProofableItem> = items.filter { item ->
        item.id.isNotBlank() &&
            item.proofStatus == ProofStatus.GENERATED &&
            !isPinned(item.id)
    }

    fun emptyQueueOutcome(
        items: List<ProofableItem>,
        isPinned: (String) -> Boolean,
    ): FilebasePreflightOutcome {
        val allAlreadyPinned = items.all { item ->
            item.id.isNotBlank() &&
                item.proofStatus == ProofStatus.GENERATED &&
                isPinned(item.id)
        }
        return if (allAlreadyPinned) {
            FilebasePreflightOutcome.AlreadyPinned
        } else {
            FilebasePreflightOutcome.EmptyAfterFilters
        }
    }

    fun queueAfterOversizeConfirm(
        remaining: List<FilebaseOnDemandCandidate>,
    ): List<FilebaseOnDemandItem> = remaining.filterNot { isUnresolvedLength(it.mediaLength) }.map { c ->
        FilebaseOnDemandItem(
            hash = c.item.id,
            uri = c.item.uri,
            mediaInclusion = if (isOversize(c.mediaLength)) {
                MediaInclusion.SIDECARS_ONLY
            } else {
                MediaInclusion.INCLUDE_MEDIA
            },
        )
    }
}
