package org.witness.proofmode.plugins.wallet.infra.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig

class ZeroDevConfigResolverTest {

    private val buildProjectId = "build-proj-uuid-0000-0000-000000000001"
    private val overrideUuid = "550e8400-e29b-41d4-a716-446655440000"
    private val chainId = "eip155:11155111"

    private fun baseSdkConfig(): WalletSdkConfig = WalletSdkConfig(
        privyAppId = "app",
        privyAppClientId = "client",
        zeroDevConfigs = mapOf(
            chainId to ZeroDevConfigResolver.resolveBuildTimeConfig(
                caip2ChainId = chainId,
                projectId = buildProjectId,
            ),
        ),
    )

    @Test
    fun effectiveProjectId_usesBuildDefaultWhenOverrideNull() {
        assertEquals(
            buildProjectId,
            ZeroDevConfigResolver.effectiveProjectId(
                sessionOverride = null,
                buildProjectId = buildProjectId,
            ),
        )
    }

    @Test
    fun effectiveProjectId_usesValidOverride() {
        assertEquals(
            overrideUuid,
            ZeroDevConfigResolver.effectiveProjectId(
                sessionOverride = overrideUuid,
                buildProjectId = buildProjectId,
            ),
        )
    }

    @Test
    fun effectiveProjectId_ignoresInvalidOverride() {
        assertEquals(
            buildProjectId,
            ZeroDevConfigResolver.effectiveProjectId(
                sessionOverride = "not-a-uuid",
                buildProjectId = buildProjectId,
            ),
        )
    }

    @Test
    fun zeroDevRpcUrl_matchesTemplate() {
        val url = ZeroDevConfigResolver.zeroDevRpcUrl(buildProjectId, chainId)
        assertEquals(
            "https://rpc.zerodev.app/api/v3/$buildProjectId/chain/11155111",
            url,
        )
    }

    @Test
    fun resolveEffectiveConfig_appliesRuntimeOverrideUrls() {
        val config = ZeroDevConfigResolver.resolveEffectiveConfig(
            chainId = chainId,
            walletSdkConfig = baseSdkConfig(),
            sessionOverride = overrideUuid,
            sponsorTransactionsEnabled = true,
        )
        assertEquals(overrideUuid, config.projectId)
        assertTrue(config.bundlerUrl.contains(overrideUuid))
        assertEquals(config.bundlerUrl, config.paymasterUrl)
        assertTrue(config.isSponsorshipEnabled)
    }

    @Test
    fun resolveEffectiveConfig_userToggleOff_sponsorshipInactive() {
        val config = ZeroDevConfigResolver.resolveEffectiveConfig(
            chainId = chainId,
            walletSdkConfig = baseSdkConfig(),
            sessionOverride = null,
            sponsorTransactionsEnabled = false,
        )
        assertFalse(config.isSponsorshipEnabled)
    }

    @Test
    fun effectiveSponsorshipAllowed_matchesAddendumFormula() {
        val credentialed = ZeroDevConfig(
            projectId = buildProjectId,
            bundlerUrl = "https://bundler",
            paymasterUrl = "https://paymaster",
            isSponsorshipEnabled = true,
        )
        assertTrue(
            ZeroDevConfigResolver.effectiveSponsorshipAllowed(credentialed),
        )
        assertFalse(
            ZeroDevConfigResolver.effectiveSponsorshipAllowed(
                credentialed.copy(isSponsorshipEnabled = false),
            ),
        )
        assertFalse(
            ZeroDevConfigResolver.effectiveSponsorshipAllowed(
                credentialed.copy(projectId = ""),
            ),
        )
    }
}
