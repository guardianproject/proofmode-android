package org.witness.proofmode.plugins.wallet.infra.config

import org.witness.proofmode.plugins.wallet.infra.BuildConfig
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig

object ZeroDevConfigResolver {

    const val RPC_URL_TEMPLATE =
        "https://rpc.zerodev.app/api/v3/%s/chain/%s"

    val SPONSORED_CHAIN_IDS: List<String> = listOf(
        "eip155:1",
        "eip155:42161",
        "eip155:8453",
        "eip155:11155111",
        "eip155:421614",
    )

    fun isConfiguredSecret(value: String): Boolean =
        value.isNotBlank() && !value.startsWith("replace-me")

    fun resolveBuildTimeProjectId(
        genericProjectId: String,
        baseFallbackProjectId: String = "",
    ): String {
        if (isConfiguredSecret(genericProjectId)) return genericProjectId
        return baseFallbackProjectId
    }

    fun zeroDevRpcUrl(projectId: String, caip2ChainId: String): String {
        val numericChainId = caip2ChainId.removePrefix("eip155:")
        return RPC_URL_TEMPLATE.format(projectId, numericChainId)
    }

    fun effectiveProjectId(
        sessionOverride: String?,
        buildProjectId: String,
    ): String =
        sessionOverride?.takeIf { UuidFormat.isValid(it) }
            ?: buildProjectId.takeIf { isConfiguredSecret(it) }
            ?: ""

    fun resolveBuildTimeConfig(
        caip2ChainId: String,
        projectId: String,
        bundlerUrlOverride: String = "",
        paymasterUrlOverride: String = "",
        isSponsorshipEnabled: Boolean = BuildConfig.FEATURE_SPONSORSHIP_ENABLED,
    ): ZeroDevConfig {
        val effectiveId = projectId.takeIf { isConfiguredSecret(it) }.orEmpty()
        val bundlerUrl = when {
            isConfiguredSecret(bundlerUrlOverride) -> bundlerUrlOverride
            effectiveId.isNotBlank() -> zeroDevRpcUrl(effectiveId, caip2ChainId)
            else -> ""
        }
        val paymasterUrl = when {
            isConfiguredSecret(paymasterUrlOverride) -> paymasterUrlOverride
            isConfiguredSecret(bundlerUrlOverride) -> bundlerUrlOverride
            effectiveId.isNotBlank() -> bundlerUrl
            else -> ""
        }
        return ZeroDevConfig(
            projectId = effectiveId,
            bundlerUrl = bundlerUrl,
            paymasterUrl = paymasterUrl,
            isSponsorshipEnabled = isSponsorshipEnabled,
        )
    }

    fun buildWalletSdkZeroDevConfigs(
        projectId: String,
        baseBundlerUrlOverride: String = "",
        basePaymasterUrlOverride: String = "",
    ): Map<String, ZeroDevConfig> =
        SPONSORED_CHAIN_IDS.associateWith { chainId ->
            val (bundlerOverride, paymasterOverride) = if (chainId == "eip155:8453") {
                baseBundlerUrlOverride to basePaymasterUrlOverride
            } else {
                "" to ""
            }
            resolveBuildTimeConfig(
                caip2ChainId = chainId,
                projectId = projectId,
                bundlerUrlOverride = bundlerOverride,
                paymasterUrlOverride = paymasterOverride,
            )
        }

    fun resolveEffectiveConfig(
        chainId: String,
        walletSdkConfig: WalletSdkConfig,
        sessionOverride: String?,
        sponsorTransactionsEnabled: Boolean,
    ): ZeroDevConfig {
        val buildChain = walletSdkConfig.zeroDevConfigs[chainId]
        val buildProjectId = buildChain?.projectId.orEmpty()
        val effectiveId = effectiveProjectId(sessionOverride, buildProjectId)

        val bundlerOverride = buildChain?.bundlerUrl
            ?.takeIf { isConfiguredSecret(it) && effectiveId == buildProjectId }
            .orEmpty()
        val paymasterOverride = buildChain?.paymasterUrl
            ?.takeIf { isConfiguredSecret(it) && effectiveId == buildProjectId }
            .orEmpty()

        return resolveBuildTimeConfig(
            caip2ChainId = chainId,
            projectId = effectiveId,
            bundlerUrlOverride = bundlerOverride,
            paymasterUrlOverride = paymasterOverride,
            isSponsorshipEnabled = BuildConfig.FEATURE_SPONSORSHIP_ENABLED &&
                sponsorTransactionsEnabled,
        )
    }

    fun resolveEffectiveConfig(
        chainId: String,
        walletSdkConfig: WalletSdkConfig,
        sessionStore: WalletSessionStore,
    ): ZeroDevConfig = resolveEffectiveConfig(
        chainId = chainId,
        walletSdkConfig = walletSdkConfig,
        sessionOverride = sessionStore.loadZeroDevProjectIdOverride(),
        sponsorTransactionsEnabled = sessionStore.isSponsorTransactionsEnabled(),
    )

    /** Addendum §B: compile gate + user pref + zero-config credential guard. */
    fun effectiveSponsorshipAllowed(config: ZeroDevConfig): Boolean =
        config.isSponsorshipEnabled &&
            config.projectId.isNotBlank() &&
            config.bundlerUrl.isNotBlank() &&
            config.paymasterUrl.isNotBlank()
}
