package org.witness.proofmode.plugins.wallet.infra.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver

class WalletSdkConfigFactoryTest {

    @Test
    fun fromBuildConfig_usesBuildConfigPrivyIdsAndAllSponsoredChains() {
        val config = WalletSdkConfig.fromBuildConfig()

        assertEquals(
            org.witness.proofmode.plugins.wallet.infra.BuildConfig.PRIVY_APP_ID,
            config.privyAppId,
        )
        assertEquals(
            org.witness.proofmode.plugins.wallet.infra.BuildConfig.PRIVY_APP_CLIENT_ID,
            config.privyAppClientId,
        )
        assertEquals(
            ZeroDevConfigResolver.SPONSORED_CHAIN_IDS.toSet(),
            config.zeroDevConfigs.keys,
        )
    }
}
