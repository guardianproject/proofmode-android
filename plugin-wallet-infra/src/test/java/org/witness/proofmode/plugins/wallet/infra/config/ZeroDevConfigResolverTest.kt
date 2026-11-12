package org.witness.proofmode.plugins.wallet.infra.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.wallet.infra.FakeAndroidKeyStoreProvider
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ZeroDevConfigResolverTest {

    private lateinit var store: WalletSessionStore

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        store = WalletSessionStore(ApplicationProvider.getApplicationContext<Context>())
    }

    private val buildProjectId = "build-proj-uuid-0000-0000-000000000001"
    private val overrideUuid = "550e8400-e29b-41d4-a716-446655440000"
    private val chainId = "eip155:11155111"

    private fun sdkConfig(projectId: String = buildProjectId): WalletSdkConfig = WalletSdkConfig(
        privyAppId = "app",
        privyAppClientId = "client",
        zeroDevProjectId = projectId,
    )

    @Test
    fun urls_matchTemplates() {
        assertEquals(
            "https://public-api.zerodev.app/v2/projects/$overrideUuid",
            ZeroDevConfigResolver.zeroDevProjectUrl(overrideUuid),
        )
        assertEquals(
            "https://rpc.zerodev.app/api/v3/$buildProjectId/chain/11155111",
            ZeroDevConfigResolver.zeroDevRpcUrl(buildProjectId, chainId),
        )
    }

    @Test
    fun effectiveProjectId_precedence() {
        listOf(
            Triple("null override uses build id", null, buildProjectId),
            Triple("valid override wins", overrideUuid, overrideUuid),
            Triple("invalid override uses build id", "not-a-uuid", buildProjectId),
        ).forEach { (label, sessionOverride, expected) ->
            assertEquals(
                label,
                expected,
                ZeroDevConfigResolver.effectiveProjectId(
                    sessionOverride = sessionOverride,
                    buildProjectId = buildProjectId,
                ),
            )
        }
    }

    @Test
    fun resolveEffectiveConfig_buildProject_templateUrls() {
        listOf("eip155:8453", chainId).forEach { chain ->
            val config = ZeroDevConfigResolver.resolveEffectiveConfig(chain, sdkConfig(), store)

            val templateUrl = ZeroDevConfigResolver.zeroDevRpcUrl(buildProjectId, chain)
            assertEquals(buildProjectId, config.projectId)
            assertEquals(chain, templateUrl, config.bundlerUrl)
            assertEquals(templateUrl, config.paymasterUrl)
        }
    }

    @Test
    fun resolveEffectiveConfig_sessionOverride_templateUrls() {
        store.saveZeroDevProjectIdOverride(overrideUuid)

        val config = ZeroDevConfigResolver.resolveEffectiveConfig("eip155:8453", sdkConfig(), store)

        val templateUrl = ZeroDevConfigResolver.zeroDevRpcUrl(overrideUuid, "eip155:8453")
        assertEquals(overrideUuid, config.projectId)
        assertEquals(templateUrl, config.bundlerUrl)
        assertEquals(templateUrl, config.paymasterUrl)
        assertTrue(config.isSponsorshipEnabled)
    }

    @Test
    fun resolveEffectiveConfig_noBuildProjectId_noOverride_blank() {
        listOf("", "replace-me-project").forEach { unconfigured ->
            val config = ZeroDevConfigResolver.resolveEffectiveConfig(
                chainId,
                sdkConfig(projectId = unconfigured),
                store,
            )

            assertEquals("id '$unconfigured'", "", config.projectId)
        }
    }

    @Test
    fun resolveEffectiveConfig_userToggleOff_sponsorshipInactive() {
        store.saveSponsorTransactionsEnabled(false)

        val config = ZeroDevConfigResolver.resolveEffectiveConfig(chainId, sdkConfig(), store)

        assertFalse(config.isSponsorshipEnabled)
    }

    private val sponsored = listOf("eip155:11155111", "eip155:1")

    @Test
    fun cachedSponsoredChains_overrideEntryWinsOverBuildId() {
        store.saveZeroDevProjectIdOverride(overrideUuid)
        store.saveSponsoredChains(overrideUuid, sponsored)

        assertEquals(sponsored, ZeroDevConfigResolver.cachedSponsoredChains(store, buildProjectId))

        // The store holds one entry, so this replaces the override's.
        store.saveSponsoredChains(buildProjectId, sponsored)

        assertNull(
            "entry for build id while override set",
            ZeroDevConfigResolver.cachedSponsoredChains(store, buildProjectId),
        )
    }

    @Test
    fun cachedSponsoredChains_noOverride_readsBuildIdEntry() {
        store.saveSponsoredChains(buildProjectId, sponsored)

        assertEquals(sponsored, ZeroDevConfigResolver.cachedSponsoredChains(store, buildProjectId))
    }
}
