package org.witness.proofmode.plugins.lp.autocapture

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCaptureLpModeTest {

    @Test
    fun fromPreference_mapsKnownValues() {
        assertEquals(AutoCaptureLpMode.OFF, AutoCaptureLpMode.fromPreference("off"))
        assertEquals(AutoCaptureLpMode.OFFCHAIN, AutoCaptureLpMode.fromPreference("offchain"))
        assertEquals(AutoCaptureLpMode.ONCHAIN, AutoCaptureLpMode.fromPreference("onchain"))
        assertEquals(AutoCaptureLpMode.BOTH, AutoCaptureLpMode.fromPreference("both"))
    }

    @Test
    fun fromPreference_unknownOrNull_defaultsToOff() {
        assertEquals(AutoCaptureLpMode.OFF, AutoCaptureLpMode.fromPreference(null))
        assertEquals(AutoCaptureLpMode.OFF, AutoCaptureLpMode.fromPreference(""))
        assertEquals(AutoCaptureLpMode.OFF, AutoCaptureLpMode.fromPreference("parallel"))
    }

    @Test
    fun toPreference_roundTrips() {
        AutoCaptureLpMode.entries.forEach { mode ->
            assertEquals(mode, AutoCaptureLpMode.fromPreference(AutoCaptureLpMode.toPreference(mode)))
        }
    }
}
