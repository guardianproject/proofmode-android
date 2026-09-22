package org.witness.proofmode.ui

import org.witness.proofmode.filebase.FilebaseOnDemandItem
import org.witness.proofmode.filebase.FilebaseOnDemandPreflight
import org.witness.proofmode.filebase.FilebasePreflightOutcome
import org.witness.proofmode.storage.filebase.FilebaseConfig

sealed class FilebaseActionBarTapEffect {
    data object ShowNotConfiguredDialog : FilebaseActionBarTapEffect()

    data object ShowProofNotReadyToast : FilebaseActionBarTapEffect()

    data object NoOp : FilebaseActionBarTapEffect()

    data class EnqueueBatch(val queue: List<FilebaseOnDemandItem>) : FilebaseActionBarTapEffect()

    data class ShowOversizeConsent(
        val oversizeCount: Int,
        val confirmQueue: List<FilebaseOnDemandItem>,
    ) : FilebaseActionBarTapEffect()
}

fun filebaseTapEffectForConfig(config: FilebaseConfig): FilebaseActionBarTapEffect? =
    if (!config.isConfigured() || config.resolveUploadMode() == FilebaseConfig.UploadMode.NONE) {
        FilebaseActionBarTapEffect.ShowNotConfiguredDialog
    } else {
        null
    }

fun filebaseTapEffectForPreflight(outcome: FilebasePreflightOutcome): FilebaseActionBarTapEffect =
    when (outcome) {
        FilebasePreflightOutcome.EmptyAfterFilters ->
            FilebaseActionBarTapEffect.ShowProofNotReadyToast
        FilebasePreflightOutcome.AlreadyPinned ->
            FilebaseActionBarTapEffect.NoOp
        is FilebasePreflightOutcome.Ready ->
            FilebaseActionBarTapEffect.EnqueueBatch(outcome.queue)
        is FilebasePreflightOutcome.NeedsOversizeConsent ->
            FilebaseActionBarTapEffect.ShowOversizeConsent(
                oversizeCount = outcome.oversizeCount,
                confirmQueue = FilebaseOnDemandPreflight.queueAfterOversizeConfirm(outcome.remaining),
            )
    }
