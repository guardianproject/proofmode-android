package org.witness.proofmode.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.witness.proofmode.ui.status.BadgeLayout
import org.witness.proofmode.ui.status.badgeLayoutForSize

class BadgeLayoutTest {
    @Test
    fun compactWhenEitherHostDimensionIsBelowEightyDp() {
        val compact = BadgeLayout(16.dp, 2.dp, 16.dp)
        assertEquals(compact, badgeLayoutForSize(79.dp, 120.dp))
        assertEquals(compact, badgeLayoutForSize(120.dp, 79.dp))
    }

    @Test
    fun regularWhenBothHostDimensionsAreAtLeastEightyDp() {
        assertEquals(BadgeLayout(24.dp, 8.dp, 18.dp), badgeLayoutForSize(80.dp, 80.dp))
    }
}