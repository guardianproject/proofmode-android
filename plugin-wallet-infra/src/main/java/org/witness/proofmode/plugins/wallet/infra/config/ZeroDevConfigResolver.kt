package org.witness.proofmode.plugins.wallet.infra.config

import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig

object ZeroDevConfigResolver {

    const val RPC_URL_TEMPLATE =
        "https://rpc.zerodev.app/api/v3/%s/chain/%s"

    const val PROJECT_URL_TEMPLATE =
        "https://public-api.zerodev.app/v2/projects/%s"

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

    fun zeroDevProjectUrl(projectId: String): String = PROJECT_URL_TEMPLATE.format(projectId)

    fun effectiveProjectId(
        sessionOverride: String?,
        buildProjectId: String,
    ): String =
        sessionOverride?.takeIf { UuidFormat.isValid(it) }
            ?: buildProjectId.takeIf { isConfiguredSecret(it) }
            ?: ""

    /**
     * The cached sponsored set for the effective project id (override, else [buildProjectId]).
     * Null means not yet known: no configured id, or no probe has answered for that id.
     */
    fun cachedSponsoredChains(
        sessionStore: WalletSessionStore,
        buildProjectId: String,
    ): List<String>? {
        val projectId = effectiveProjectId(sessionStore.loadZeroDevProjectIdOverride(), buildProjectId)
        return sessionStore.loadSponsoredChains(projectId)
    }

    fun resolveEffectiveConfig(
        chainId: String,
        walletSdkConfig: WalletSdkConfig,
        sessionStore: WalletSessionStore,
    ): ZeroDevConfig {
        val projectId = effectiveProjectId(
            sessionStore.loadZeroDevProjectIdOverride(),
            walletSdkConfig.zeroDevProjectId,
        )
        val rpcUrl = if (projectId.isNotBlank()) zeroDevRpcUrl(projectId, chainId) else ""
        return ZeroDevConfig(
            projectId = projectId,
            bundlerUrl = rpcUrl,
            paymasterUrl = rpcUrl,
            isSponsorshipEnabled = sessionStore.isSponsorTransactionsEnabled(),
        )
    }
}
