package org.witness.proofmode.ui

import android.net.FakeUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProofsetActionBarTest {
    @Test
    fun actionBarItems_usesSelectionWhenAnySelected() {
        val current = ProofableItem("cur", FakeUri(), ProofStatus.GENERATED)
        val selected = listOf(ProofableItem("s", FakeUri(), ProofStatus.GENERATED))
        assertEquals(selected, actionBarItems(anySelected = true, selectedItems = selected, currentItem = current))
    }

    @Test
    fun actionBarItems_usesCurrentWhenNoneSelected() {
        val current = ProofableItem("cur", FakeUri(), ProofStatus.GENERATED)
        assertEquals(listOf(current), actionBarItems(anySelected = false, selectedItems = emptyList(), currentItem = current))
    }

    @Test
    fun lpActionIconsVisible_falseWhenLpInactive() {
        assertFalse(lpActionIconsVisible(false))
    }
}
