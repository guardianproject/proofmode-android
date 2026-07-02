package org.witness.proofmode

import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureFlagRegistrationTest {

    @Test
    fun `lp disabled skips plugin registration`() {
        var lpRegisterCalls = 0

        registerExperimentalPluginsIfEnabled(
            lpEnabled = false,
            registerLocationProtocol = { lpRegisterCalls++ }
        )

        assertEquals(0, lpRegisterCalls)
    }
}
