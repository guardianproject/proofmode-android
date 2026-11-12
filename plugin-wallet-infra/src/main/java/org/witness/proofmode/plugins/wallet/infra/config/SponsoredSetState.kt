package org.witness.proofmode.plugins.wallet.infra.config

/**
 * What a screen knows about the effective project's sponsored chains. [CheckFailed] is a screen
 * state only: it is never cached, so a later open starts from [NotYetKnown] again.
 * [Known.ids] is a list, not a set, because it keeps the catalog order the probe was asked in,
 * so a reader can take "the first sponsored chain" without importing the catalog. An empty
 * list is an answer, not a gap.
 */
sealed class SponsoredSetState {
    data object NotYetKnown : SponsoredSetState()
    data object CheckFailed : SponsoredSetState()
    data class Known(val ids: List<String>) : SponsoredSetState()
}
