package org.witness.proofmode.plugins.wallet.infra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderId
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig
import org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnector

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletProviderFactoryTest {

    /**
     * Integration test `restoreBackgroundSession_doesNotRequireSetChainForSponsorshipFlag` is skipped:
     * [WalletProviderFactory.createDefault] constructs a real [org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector]
     * with no injection point for test doubles; stubbing `ensurePrivyBackground` requires broader factory refactor.
     * Cold-start sponsorship restore is covered by
     * [org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnectorTest.coldStartRestore_activatesSponsorshipWhenPrivyReconnectsWithoutSetChain].
     */

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun createDefault_usesRestoredChainId() {
        val store = mock<WalletSessionStore>()
        whenever(store.loadChainId()).thenReturn("eip155:84532")
        val config = WalletSdkConfig("app", "client", defaultChainId = "eip155:1")
        val selection = WalletProviderFactory.createDefault(config, store)
        assertEquals(WalletProviderId.ZERODEV, selection.selectedProvider)
        val privy = WalletProviderFactory.privyConnector(selection)
        assertNull(privy.getIdentity())
        assertEquals("eip155:84532", privy.getSelectedChainId())
    }

    @Test
    fun createDefault_fallsBackToConfigDefaultChainId_whenStoreEmpty() {
        val store = mock<WalletSessionStore>()
        whenever(store.loadChainId()).thenReturn(null)
        whenever(store.isSponsorTransactionsEnabled()).thenReturn(true)
        val config = WalletSdkConfig("app", "client")
        val selection = WalletProviderFactory.createDefault(config, store)
        assertEquals(
            "eip155:11155111",
            WalletProviderFactory.privyConnector(selection).getSelectedChainId(),
        )
    }

    @Test
    fun createDefault_sponsorshipOn_remapsLeftoverMainnetBeforePrivyConstruct() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = WalletSessionStore(context)
        store.saveSponsorTransactionsEnabled(true)
        store.saveChainId("eip155:1")
        val config = WalletSdkConfig("app", "client")
        val selection = WalletProviderFactory.createDefault(config, store)
        assertEquals("eip155:11155111", store.loadChainId())
        assertEquals(
            "eip155:11155111",
            WalletProviderFactory.privyConnector(selection).getSelectedChainId(),
        )
    }

    @Test
    fun createDefault_sponsorshipOn_remapsArbitrumOneAndBase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("eip155:42161", "eip155:8453").forEach { leftover ->
            val store = WalletSessionStore(context)
            store.clear()
            store.saveSponsorTransactionsEnabled(true)
            store.saveChainId(leftover)
            WalletProviderFactory.createDefault(WalletSdkConfig("app", "client"), store)
            assertEquals("eip155:11155111", store.loadChainId())
        }
    }

    @Test
    fun createDefault_sponsorshipOff_preservesLeftoverMainnet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = WalletSessionStore(context)
        store.saveSponsorTransactionsEnabled(false)
        store.saveChainId("eip155:1")
        val selection = WalletProviderFactory.createDefault(WalletSdkConfig("app", "client"), store)
        assertEquals("eip155:1", store.loadChainId())
        assertEquals(
            "eip155:1",
            WalletProviderFactory.privyConnector(selection).getSelectedChainId(),
        )
    }

    @Test
    fun refreshSponsorship_sponsorshipOn_remapsStoreLeftoverThenSetChainSepolia() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = WalletSessionStore(context)
        store.saveSponsorTransactionsEnabled(false)
        store.saveChainId("eip155:1")
        val selection = WalletProviderFactory.createDefault(WalletSdkConfig("app", "client"), store)
        assertEquals("eip155:1", store.loadChainId())

        store.saveSponsorTransactionsEnabled(true)
        WalletProviderFactory.refreshSponsorshipForCurrentChain(selection, store)

        assertEquals("eip155:11155111", store.loadChainId())
        assertEquals(
            "eip155:11155111",
            WalletProviderFactory.privyConnector(selection).getSelectedChainId(),
        )
    }

    @Test
    fun refreshSponsorship_sponsorshipOff_doesNotRemap() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = WalletSessionStore(context)
        store.saveSponsorTransactionsEnabled(false)
        store.saveChainId("eip155:42161")
        val selection = WalletProviderFactory.createDefault(WalletSdkConfig("app", "client"), store)

        WalletProviderFactory.refreshSponsorshipForCurrentChain(selection, store)

        assertEquals("eip155:42161", store.loadChainId())
        assertEquals(
            "eip155:42161",
            WalletProviderFactory.privyConnector(selection).getSelectedChainId(),
        )
    }

    @Test
    fun configResolver_respectsSponsorTransactionsDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionStore = WalletSessionStore(context)
        sessionStore.saveSponsorTransactionsEnabled(false)
        val sdkConfig = WalletSdkConfig(
            privyAppId = "app",
            privyAppClientId = "client",
            zeroDevConfigs = mapOf(
                "eip155:1" to ZeroDevConfig(
                    projectId = "550e8400-e29b-41d4-a716-446655440000",
                    bundlerUrl = "https://bundler",
                    paymasterUrl = "https://paymaster",
                ),
            ),
        )
        WalletProviderFactory.createDefault(sdkConfig, sessionStore)
        val resolved = ZeroDevConfigResolver.resolveEffectiveConfig(
            "eip155:1",
            sdkConfig,
            sessionStore,
        )
        assertFalse(resolved.isSponsorshipEnabled)
    }
}
