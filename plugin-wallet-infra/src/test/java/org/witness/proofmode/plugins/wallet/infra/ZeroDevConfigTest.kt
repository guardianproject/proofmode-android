package org.witness.proofmode.plugins.wallet.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig

class ZeroDevConfigTest {

    @Test
    fun testDefaultValuesAndAccessors() {
        val config = ZeroDevConfig(
            projectId = "proj_123",
            bundlerUrl = "https://bundler.com",
            paymasterUrl = "https://paymaster.com"
        )
        assertEquals("proj_123", config.projectId)
        assertEquals("https://bundler.com", config.bundlerUrl)
        assertEquals("https://paymaster.com", config.paymasterUrl)
        // Defaults to BuildConfig.FEATURE_SPONSORSHIP_ENABLED which is true in debug build
        assertTrue(config.isSponsorshipEnabled)
    }

    @Test
    fun testCustomSponsorship() {
        val config = ZeroDevConfig(
            projectId = "proj_123",
            bundlerUrl = "https://bundler.com",
            paymasterUrl = "https://paymaster.com",
            isSponsorshipEnabled = false
        )
        assertEquals(false, config.isSponsorshipEnabled)
    }

    @Test
    fun testCopyBehavior() {
        val original = ZeroDevConfig(
            projectId = "proj_1",
            bundlerUrl = "https://b1",
            paymasterUrl = "https://p1",
            isSponsorshipEnabled = true
        )
        val copy = original.copy(projectId = "proj_2")
        assertEquals("proj_2", copy.projectId)
        assertEquals(original.bundlerUrl, copy.bundlerUrl)
        assertEquals(original.paymasterUrl, copy.paymasterUrl)
        assertEquals(original.isSponsorshipEnabled, copy.isSponsorshipEnabled)
    }

    @Test
    fun testEqualityAndHashCode() {
        val config1 = ZeroDevConfig("p1", "b1", "pay1", true)
        val config2 = ZeroDevConfig("p1", "b1", "pay1", true)
        val config3 = ZeroDevConfig("p2", "b1", "pay1", true)

        assertEquals(config1, config2)
        assertNotEquals(config1, config3)
        assertEquals(config1.hashCode(), config2.hashCode())
        assertNotEquals(config1.hashCode(), config3.hashCode())
    }
}
