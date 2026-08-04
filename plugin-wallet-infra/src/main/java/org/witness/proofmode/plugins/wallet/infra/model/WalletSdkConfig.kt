package org.witness.proofmode.plugins.wallet.infra.model

import org.witness.proofmode.plugins.wallet.infra.BuildConfig
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver

data class WalletSdkConfig(
    val privyAppId: String,
    val privyAppClientId: String,
    val defaultChainId: String = "eip155:1",
    val zeroDevConfigs: Map<String, ZeroDevConfig> = emptyMap(),
) {
    companion object {
        /**
         * Assemble the runtime wallet SDK config from wallet-infra's own
         * BuildConfig secrets via [ZeroDevConfigResolver]. This is the single
         * bootstrap entry point — callers (LP) must not read app BuildConfig.
         */
        fun fromBuildConfig(): WalletSdkConfig {
            val projectId = ZeroDevConfigResolver.resolveBuildTimeProjectId(
                genericProjectId = BuildConfig.ZERODEV_PROJECT_ID,
                baseFallbackProjectId = BuildConfig.ZERODEV_PROJECT_ID_BASE,
            )
            val zeroDevConfigs = ZeroDevConfigResolver.buildWalletSdkZeroDevConfigs(
                projectId = projectId,
                baseBundlerUrlOverride = BuildConfig.ZERODEV_BUNDLER_URL_BASE,
                basePaymasterUrlOverride = BuildConfig.ZERODEV_PAYMASTER_URL_BASE,
            )
            return WalletSdkConfig(
                privyAppId = BuildConfig.PRIVY_APP_ID,
                privyAppClientId = BuildConfig.PRIVY_APP_CLIENT_ID,
                zeroDevConfigs = zeroDevConfigs,
            )
        }
    }
}