package org.witness.proofmode.plugins.wallet.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig

class WalletSdkConfigExtendedTest {

    @Test
    fun testDefaultZeroDevConfigsIsEmpty() {
        val config = WalletSdkConfig(
            privyAppId = "app_123",
            privyAppClientId = "client_123"
        )
        assertEquals("app_123", config.privyAppId)
        assertEquals("client_123", config.privyAppClientId)
        assertEquals("eip155:1", config.defaultChainId)
        assertTrue(config.zeroDevConfigs.isEmpty())
    }

    @Test
    fun testPopulatedZeroDevConfigs() {
        val zeroDevConfig = ZeroDevConfig(
            projectId = "zero_123",
            bundlerUrl = "https://bundler",
            paymasterUrl = "https://paymaster"
        )
        val config = WalletSdkConfig(
            privyAppId = "app_123",
            privyAppClientId = "client_123",
            defaultChainId = "eip155:137",
            zeroDevConfigs = mapOf("eip155:137" to zeroDevConfig)
        )
        assertEquals(1, config.zeroDevConfigs.size)
        assertEquals(zeroDevConfig, config.zeroDevConfigs["eip155:137"])
    }

    @Test
    fun testBackwardCompatibility() {
        // Constructing only with the original three positional fields
        val config = WalletSdkConfig(
            privyAppId = "app_123",
            privyAppClientId = "client_123",
            defaultChainId = "eip155:1"
        )
        assertEquals("app_123", config.privyAppId)
        assertEquals("client_123", config.privyAppClientId)
        assertEquals("eip155:1", config.defaultChainId)
        assertTrue(config.zeroDevConfigs.isEmpty())
    }
}
