package org.witness.proofmode.ui

import android.net.FakeUri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.witness.proofmode.R
import org.witness.proofmode.lp.LpManualLeg

class ProofsetActionBarTest {
    @Test
    fun actionBarItems_usesSelectionElseCurrent() {
        val current = ProofableItem("cur", FakeUri(), ProofStatus.GENERATED)
        val selected = listOf(ProofableItem("s", FakeUri(), ProofStatus.GENERATED))
        assertEquals(selected, actionBarItems(anySelected = true, selectedItems = selected, currentItem = current))
        assertEquals(listOf(current), actionBarItems(anySelected = false, selectedItems = emptyList(), currentItem = current))
    }

    @Test
    fun tapToastRes_picksPerOutcome() {
        assertEquals(R.string.lp_toast_offchain_success, lpTapToastRes(LpManualLeg.OFFCHAIN, succeeded = 3, total = 3))
        assertEquals(R.string.lp_toast_offchain_failed, lpTapToastRes(LpManualLeg.OFFCHAIN, succeeded = 0, total = 3))
        assertEquals(R.string.proofset_toast_mixed, lpTapToastRes(LpManualLeg.OFFCHAIN, succeeded = 2, total = 3))
        assertEquals(R.string.lp_toast_onchain_success, lpTapToastRes(LpManualLeg.ONCHAIN, succeeded = 1, total = 1))
        assertEquals(R.string.lp_toast_onchain_failed, lpTapToastRes(LpManualLeg.ONCHAIN, succeeded = 0, total = 1))
        assertEquals(R.string.proofset_toast_mixed, lpTapToastRes(LpManualLeg.ONCHAIN, succeeded = 1, total = 2))
    }
}
