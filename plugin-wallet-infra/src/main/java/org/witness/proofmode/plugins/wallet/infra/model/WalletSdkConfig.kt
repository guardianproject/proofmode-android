package org.witness.proofmode.plugins.wallet.infra.model

import org.witness.proofmode.plugins.wallet.infra.BuildConfig
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver

data class WalletSdkConfig(
    val privyAppId: String,
    val privyAppClientId: String,
    val defaultChainId: String = WalletChainPolicy.DEFAULT_CHAIN_ID,
    val zeroDevProjectId: String = "",
) {
    companion object {
        /**
         * Assemble the runtime wallet SDK config from wallet-infra's own
         * BuildConfig secrets via [ZeroDevConfigResolver]. This is the single
         * bootstrap entry point — callers (LP) must not read app BuildConfig.
         */
        fun fromBuildConfig(): WalletSdkConfig {
            return WalletSdkConfig(
                privyAppId = BuildConfig.PRIVY_APP_ID,
                privyAppClientId = BuildConfig.PRIVY_APP_CLIENT_ID,
                defaultChainId = WalletChainPolicy.DEFAULT_CHAIN_ID,
                zeroDevProjectId = ZeroDevConfigResolver.resolveBuildTimeProjectId(
                    genericProjectId = BuildConfig.ZERODEV_PROJECT_ID,
                    baseFallbackProjectId = BuildConfig.ZERODEV_PROJECT_ID_BASE,
                ),
            )
        }
    }
}
