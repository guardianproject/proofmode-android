package org.witness.proofmode

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.witness.proofmode.plugins.ipfscid.IpfsCidPlugin

class IpfsCidPluginWiringTest {
    @Test
    fun proofModeAppDependency_canReferenceIpfsCidPlugin() {
        assertNotNull(IpfsCidPlugin::class.java)
    }
}
