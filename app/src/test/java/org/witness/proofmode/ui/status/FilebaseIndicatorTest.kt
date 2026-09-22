package org.witness.proofmode.ui.status

import org.junit.Assert.assertEquals
import org.junit.Test

class FilebaseIndicatorTest {
    @Test
    fun spinnerWhenOccupancyPositive() {
        assertEquals(
            FilebaseIndicator.SPINNER,
            filebaseIndicator(occupancy = 1, hasSuccessSidecar = true),
        )
        assertEquals(
            FilebaseIndicator.SPINNER,
            filebaseIndicator(occupancy = 2, hasSuccessSidecar = false),
        )
    }

    @Test
    fun badgeWhenIdleAndSuccessSidecar() {
        assertEquals(
            FilebaseIndicator.BADGE,
            filebaseIndicator(occupancy = 0, hasSuccessSidecar = true),
        )
    }

    @Test
    fun noneWhenIdleWithoutSidecar_includesParkedS3SidecarsOnlyGap() {
        assertEquals(
            FilebaseIndicator.NONE,
            filebaseIndicator(occupancy = 0, hasSuccessSidecar = false),
        )
    }
}
