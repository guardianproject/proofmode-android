package org.witness.proofmode.plugins.lp.config

import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy

object ChainSpinnerPolicy {

    fun visibleChains(
        sponsorshipOn: Boolean,
        hasEffectiveProjectId: Boolean,
        compileSponsorshipEnabled: Boolean = true,
        catalog: List<ChainConfig> = SUPPORTED_CHAINS,
    ): List<ChainConfig> {
        if (!compileSponsorshipEnabled || !sponsorshipOn) return catalog
        if (!hasEffectiveProjectId) return emptyList()
        val allowed = WalletChainPolicy.SPONSORED_VISIBLE_CHAIN_IDS.toSet()
        return catalog.filter { it.caip2Id in allowed }
    }

    fun showEmptySponsoredNote(
        sponsorshipOn: Boolean,
        hasEffectiveProjectId: Boolean,
        compileSponsorshipEnabled: Boolean = true,
    ): Boolean = compileSponsorshipEnabled && sponsorshipOn && !hasEffectiveProjectId

    /**
     * Index into [visible]. Missing or leftover saved ids fall back to Sepolia, then
     * first remaining sponsored row in catalog order. Empty [visible] returns 0
     * (spinner is disabled; adapter may be empty).
     */
    fun defaultSelectionIndex(
        visible: List<ChainConfig>,
        savedChainId: String?,
    ): Int {
        if (visible.isEmpty()) return 0
        val preferred = savedChainId ?: WalletChainPolicy.DEFAULT_CHAIN_ID
        val exact = visible.indexOfFirst { it.caip2Id == preferred }
        if (exact >= 0) return exact
        val sepolia = visible.indexOfFirst { it.caip2Id == WalletChainPolicy.DEFAULT_CHAIN_ID }
        if (sepolia >= 0) return sepolia
        return 0
    }
}
