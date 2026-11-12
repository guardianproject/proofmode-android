package org.witness.proofmode.plugins.lp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.config.SponsoredSetState
import org.witness.proofmode.plugins.wallet.infra.config.SponsoredSetState.CheckFailed
import org.witness.proofmode.plugins.wallet.infra.config.SponsoredSetState.Known
import org.witness.proofmode.plugins.wallet.infra.config.SponsoredSetState.NotYetKnown
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy

class ChainSpinnerPolicyTest {

    private val catalogIds = SUPPORTED_CHAINS.map { it.caip2Id }

    @Test
    fun sponsorshipOff_ignoresSetAndShowsCatalog() {
        for (hasProject in listOf(true, false)) {
            for (set in listOf(NotYetKnown, CheckFailed, Known(emptyList()), Known(listOf(SEPOLIA)))) {
                val case = "project=$hasProject set=$set"
                val visible = ChainSpinnerPolicy.visibleChains(
                    sponsorshipOn = false,
                    hasEffectiveProjectId = hasProject,
                    sponsoredSet = set,
                )
                assertEquals(case, catalogIds, visible.map { it.caip2Id })
                assertNull(
                    case,
                    ChainSpinnerPolicy.note(
                        sponsorshipOn = false,
                        hasEffectiveProjectId = hasProject,
                        sponsoredSet = set,
                    ),
                )
                assertFalse(
                    case,
                    ChainSpinnerPolicy.shouldProbe(sponsorshipOn = false, hasEffectiveProjectId = hasProject),
                )
            }
        }

        assertTrue(ChainSpinnerPolicy.shouldProbe(sponsorshipOn = true, hasEffectiveProjectId = true))
        assertFalse(ChainSpinnerPolicy.shouldProbe(sponsorshipOn = true, hasEffectiveProjectId = false))
    }

    @Test
    fun emptyStates_pickTheirNote() {
        data class Row(
            val hasProject: Boolean,
            val set: SponsoredSetState,
            val expected: ChainSpinnerPolicy.NoteState,
        )
        val rows = listOf(
            Row(true, Known(emptyList()), ChainSpinnerPolicy.NoteState.NoSponsored),
            Row(true, CheckFailed, ChainSpinnerPolicy.NoteState.CouldNotCheck),
            Row(true, NotYetKnown, ChainSpinnerPolicy.NoteState.Checking),
            // Without a project id the screen never asks, so it never says "could not check".
            Row(false, NotYetKnown, ChainSpinnerPolicy.NoteState.NoSponsored),
            Row(false, CheckFailed, ChainSpinnerPolicy.NoteState.NoSponsored),
            Row(false, Known(listOf(SEPOLIA)), ChainSpinnerPolicy.NoteState.NoSponsored),
        )
        for ((hasProject, set, expected) in rows) {
            val case = "project=$hasProject set=$set"
            assertTrue(
                case,
                ChainSpinnerPolicy.visibleChains(
                    sponsorshipOn = true,
                    hasEffectiveProjectId = hasProject,
                    sponsoredSet = set,
                ).isEmpty(),
            )
            assertEquals(
                case,
                expected,
                ChainSpinnerPolicy.note(
                    sponsorshipOn = true,
                    hasEffectiveProjectId = hasProject,
                    sponsoredSet = set,
                ),
            )
        }
    }

    @Test
    fun manySponsoredChains_comeBackInCatalogOrder() {
        val reversed = listOf(ARB_SEPOLIA, NOT_IN_CATALOG, SEPOLIA, BASE, MAINNET)
        val visible = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = true,
            hasEffectiveProjectId = true,
            sponsoredSet = Known(reversed),
        )
        assertEquals(
            catalogIds.filter { it in reversed },
            visible.map { it.caip2Id },
        )
        assertFalse(visible.any { it.caip2Id == NOT_IN_CATALOG })
        assertNull(
            ChainSpinnerPolicy.note(
                sponsorshipOn = true,
                hasEffectiveProjectId = true,
                sponsoredSet = Known(reversed),
            ),
        )
    }

    @Test
    fun defaultSelection_fallsBackFromSavedToSepoliaToFirstRow() {
        val withSepolia = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = true,
            hasEffectiveProjectId = true,
            sponsoredSet = Known(listOf(MAINNET, SEPOLIA, ARB_SEPOLIA)),
        )
        val sepoliaIndex = withSepolia.indexOfFirst { it.caip2Id == SEPOLIA }
        assertTrue(sepoliaIndex > 0)
        assertEquals(sepoliaIndex, ChainSpinnerPolicy.defaultSelectionIndex(withSepolia, savedChainId = null))
        assertEquals(sepoliaIndex, ChainSpinnerPolicy.defaultSelectionIndex(withSepolia, savedChainId = BASE))
        assertEquals(
            withSepolia.indexOfFirst { it.caip2Id == ARB_SEPOLIA },
            ChainSpinnerPolicy.defaultSelectionIndex(withSepolia, savedChainId = ARB_SEPOLIA),
        )

        val withoutSepolia = ChainSpinnerPolicy.visibleChains(
            sponsorshipOn = true,
            hasEffectiveProjectId = true,
            sponsoredSet = Known(listOf(ARB_SEPOLIA, BASE)),
        )
        assertEquals(0, ChainSpinnerPolicy.defaultSelectionIndex(withoutSepolia, savedChainId = null))
        assertEquals(0, ChainSpinnerPolicy.defaultSelectionIndex(withoutSepolia, savedChainId = SEPOLIA))
    }

    private companion object {
        const val MAINNET = "eip155:1"
        const val BASE = "eip155:8453"
        const val SEPOLIA = WalletChainPolicy.DEFAULT_CHAIN_ID
        const val ARB_SEPOLIA = "eip155:421614"

        /** Polygon: a sponsored id the catalog does not carry, so the filter must drop it. */
        const val NOT_IN_CATALOG = "eip155:137"
    }
}
