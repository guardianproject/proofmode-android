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
    data class Ready(val queue: List<FilebaseOnDemandItem>) : FilebasePreflightOutcome()
    data class NeedsOversizeConsent(
        val remaining: List<FilebaseOnDemandCandidate>,
        val oversizeCount: Int,
    ) : FilebasePreflightOutcome()
}

/** Generated tapped items split by whether their Filebase success sidecar already exists. */
data class FilebasePinPartition(
    val unpinned: List<ProofableItem>,
    val pinnedHashes: List<String>,
)

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

    /**
     * Splits the generated, non-blank tapped items by pin state in one pass. [isPinned] is a
     * sidecar read, so it runs once per item.
     */
    fun partitionByPin(
        items: List<ProofableItem>,
        isPinned: (String) -> Boolean,
    ): FilebasePinPartition {
        val unpinned = mutableListOf<ProofableItem>()
        val pinnedHashes = mutableListOf<String>()
        for (item in items) {
            if (item.id.isBlank() || item.proofStatus != ProofStatus.GENERATED) continue
            if (isPinned(item.id)) pinnedHashes += item.id else unpinned += item
        }
        return FilebasePinPartition(unpinned, pinnedHashes)
    }

    fun emptyQueueOutcome(
        items: List<ProofableItem>,
        pinnedHashes: List<String>,
    ): FilebasePreflightOutcome =
        if (pinnedHashes.size == items.size) {
            // Empty queue on purpose: the tap still registers so it can toast that all are uploaded.
            FilebasePreflightOutcome.Ready(emptyList())
        } else {
            FilebasePreflightOutcome.EmptyAfterFilters
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
