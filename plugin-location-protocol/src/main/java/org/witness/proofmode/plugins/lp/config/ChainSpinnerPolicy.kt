package org.witness.proofmode.plugins.lp.config

import org.witness.proofmode.plugins.wallet.infra.config.SponsoredSetState
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy

/**
 * What the Wallet Settings chain spinner offers, and the one note that explains an empty or
 * pending spinner. [SponsoredSetState] separates "not asked yet", "asked and failed", and
 * "asked and the answer may be empty", so the three empty states cannot be confused.
 */
object ChainSpinnerPolicy {

    sealed class NoteState {
        data object Checking : NoteState()
        data object NoSponsored : NoteState()
        data object CouldNotCheck : NoteState()
    }

    fun visibleChains(
        sponsorshipOn: Boolean,
        hasEffectiveProjectId: Boolean,
        sponsoredSet: SponsoredSetState,
    ): List<ChainConfig> {
        if (!sponsorshipOn) return SUPPORTED_CHAINS
        if (!hasEffectiveProjectId) return emptyList()
        return when (sponsoredSet) {
            is SponsoredSetState.Known -> SUPPORTED_CHAINS.filter { it.caip2Id in sponsoredSet.ids }
            SponsoredSetState.NotYetKnown, SponsoredSetState.CheckFailed -> emptyList()
        }
    }

    fun note(
        sponsorshipOn: Boolean,
        hasEffectiveProjectId: Boolean,
        sponsoredSet: SponsoredSetState,
    ): NoteState? {
        if (!sponsorshipOn) return null
        if (!hasEffectiveProjectId) return NoteState.NoSponsored
        return when (sponsoredSet) {
            SponsoredSetState.NotYetKnown -> NoteState.Checking
            SponsoredSetState.CheckFailed -> NoteState.CouldNotCheck
            is SponsoredSetState.Known -> NoteState.NoSponsored.takeIf { sponsoredSet.ids.isEmpty() }
        }
    }

    fun shouldProbe(
        sponsorshipOn: Boolean,
        hasEffectiveProjectId: Boolean,
    ): Boolean = sponsorshipOn && hasEffectiveProjectId

    /**
     * Index into [visible]. A saved id that is missing or not visible falls back to Sepolia,
     * then to the first visible row. An empty [visible] falls through to 0 (spinner is disabled).
     */
    fun defaultSelectionIndex(
        visible: List<ChainConfig>,
        savedChainId: String?,
    ): Int {
        val preferred = savedChainId ?: WalletChainPolicy.DEFAULT_CHAIN_ID
        val exact = visible.indexOfFirst { it.caip2Id == preferred }
        if (exact >= 0) return exact
        val sepolia = visible.indexOfFirst { it.caip2Id == WalletChainPolicy.DEFAULT_CHAIN_ID }
        if (sepolia >= 0) return sepolia
        return 0
    }
}
