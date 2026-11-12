package org.witness.proofmode.plugins.wallet.infra.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.BuildConfig
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver

class WalletSdkConfigFactoryTest {

    @Test
    fun fromBuildConfig_carriesBuildConfigFields() {
        val config = WalletSdkConfig.fromBuildConfig()

        assertEquals(
            BuildConfig.PRIVY_APP_ID,
            config.privyAppId,
        )
        assertEquals(
            BuildConfig.PRIVY_APP_CLIENT_ID,
            config.privyAppClientId,
        )
        assertEquals("eip155:11155111", config.defaultChainId)
        val expected = ZeroDevConfigResolver.resolveBuildTimeProjectId(
            genericProjectId = BuildConfig.ZERODEV_PROJECT_ID,
            baseFallbackProjectId = BuildConfig.ZERODEV_PROJECT_ID_BASE,
        )
        assertEquals(expected, config.zeroDevProjectId)
    }
}
