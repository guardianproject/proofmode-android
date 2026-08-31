package org.witness.proofmode.plugins.lp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy

class ChainSpinnerPolicyTest {

    @Test
    fun catalog_isUnchangedFiveRows() {
        assertEquals(5, SUPPORTED_CHAINS.size)
        assertTrue(SUPPORTED_CHAINS.any { it.caip2Id == "eip155:1" })
        assertTrue(SUPPORTED_CHAINS.any { it.caip2Id == "eip155:42161" })
        assertTrue(SUPPORTED_CHAINS.any { it.caip2Id == "eip155:8453" })
        assertTrue(SUPPORTED_CHAINS.any { it.caip2Id == "eip155:11155111" })
        assertTrue(SUPPORTED_CHAINS.any { it.caip2Id == "eip155:421614" })
    }

    @Test
    fun sponsorshipOff_showsFullCatalog() {
        val visible = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = false,
            hasEffectiveProjectId = true,
        )
        assertEquals(SUPPORTED_CHAINS.map { it.caip2Id }, visible.map { it.caip2Id })
    }

    @Test
    fun compileSponsorshipDisabled_showsFullCatalogEvenIfToggleOn() {
        val visible = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = true,
            hasEffectiveProjectId = false,
            compileSponsorshipEnabled = false,
        )
        assertEquals(5, visible.size)
        assertFalse(
            ChainSpinnerPolicy.showEmptySponsoredNote(
                sponsorshipOn = true,
                hasEffectiveProjectId = false,
                compileSponsorshipEnabled = false,
            ),
        )
    }

    @Test
    fun sponsorshipOn_withProjectId_showsOnlySepoliaAndArbitrumSepolia() {
        val visible = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = true,
            hasEffectiveProjectId = true,
        )
        assertEquals(
            listOf("eip155:11155111", "eip155:421614"),
            visible.map { it.caip2Id },
        )
        assertEquals(WalletChainPolicy.SPONSORED_VISIBLE_CHAIN_IDS, visible.map { it.caip2Id })
    }

    @Test
    fun sponsorshipOn_withoutProjectId_emptyAndShowsNote() {
        val visible = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = true,
            hasEffectiveProjectId = false,
        )
        assertTrue(visible.isEmpty())
        assertTrue(
            ChainSpinnerPolicy.showEmptySponsoredNote(
                sponsorshipOn = true,
                hasEffectiveProjectId = false,
            ),
        )
    }

    @Test
    fun defaultSelection_prefersSepoliaWhenSavedMissingOrLeftover() {
        val visible = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = true,
            hasEffectiveProjectId = true,
        )
        assertEquals(0, ChainSpinnerPolicy.defaultSelectionIndex(visible, savedChainId = null))
        assertEquals(
            0,
            ChainSpinnerPolicy.defaultSelectionIndex(visible, savedChainId = "eip155:1"),
        )
        assertEquals(
            1,
            ChainSpinnerPolicy.defaultSelectionIndex(visible, savedChainId = "eip155:421614"),
        )
    }

    @Test
    fun defaultSelection_ifSepoliaAbsent_usesFirstRemainingSponsored() {
        val withoutSepolia = SUPPORTED_CHAINS.filter { it.caip2Id == "eip155:421614" }
        assertEquals(
            0,
            ChainSpinnerPolicy.defaultSelectionIndex(withoutSepolia, savedChainId = null),
        )
    }
}
