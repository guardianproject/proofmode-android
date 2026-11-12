package org.witness.proofmode.plugins.wallet.infra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderSelection
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnector

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletProviderFactoryTest {

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

    private fun storeWithCache(sponsorshipOn: Boolean, storedChain: String?, cache: List<String>?): WalletSessionStore {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext<Context>())
        store.saveSponsorTransactionsEnabled(sponsorshipOn)
        store.saveZeroDevProjectIdOverride(BUILD_PROJECT_ID)
        storedChain?.let(store::saveChainId)
        cache?.let { store.saveSponsoredChains(BUILD_PROJECT_ID, it) }
        return store
    }

    @Test
    fun createDefault_sponsorshipOn_cacheExcludesStoredMainnet_remapsAndPersists() {
        val store = storeWithCache(true, "eip155:1", listOf(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:421614"))

        // A config default other than the remap target, so the Privy assert only holds when the
        // connector is built from the resolved chain and not from the config.
        val selection = WalletProviderFactory.createDefault(
            WalletSdkConfig("app", "client", defaultChainId = "eip155:1"),
            store,
        )

        assertEquals(WalletChainPolicy.DEFAULT_CHAIN_ID, store.loadChainId())
        assertEquals(
            WalletChainPolicy.DEFAULT_CHAIN_ID,
            WalletProviderFactory.privyConnector(selection).getSelectedChainId(),
        )
    }

    @Test
    fun createDefault_keepsStoredChain() {
        // (label, sponsorship on, stored chain, cached sponsored set, config default chain)
        // The no-cache row carries a config default that differs from its stored chain, so it also
        // pins that the stored chain wins over the config default.
        data class Row(
            val label: String,
            val sponsorshipOn: Boolean,
            val storedChain: String,
            val cache: List<String>?,
            val configDefault: String,
        )
        listOf(
            // No-cache runs first: the rows share one prefs file, so a later row would see an earlier row's cache.
            Row("sponsorOn + no-cache", true, "eip155:42161", null, "eip155:1"),
            Row("sponsorOn + in-set", true, "eip155:1", listOf(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:1"), WalletChainPolicy.DEFAULT_CHAIN_ID),
            Row("sponsorOff + excluded", false, "eip155:1", listOf(WalletChainPolicy.DEFAULT_CHAIN_ID), WalletChainPolicy.DEFAULT_CHAIN_ID),
        ).forEach { row ->
            val store = storeWithCache(row.sponsorshipOn, row.storedChain, row.cache)

            val selection = WalletProviderFactory.createDefault(
                WalletSdkConfig("app", "client", defaultChainId = row.configDefault),
                store,
            )

            assertEquals("case: ${row.label}", row.storedChain, store.loadChainId())
            assertEquals(
                "case: ${row.label}",
                row.storedChain,
                WalletProviderFactory.privyConnector(selection).getSelectedChainId(),
            )
        }
    }

    @Test
    fun createDefault_cacheKeyedByBuildProjectIdFromConfig() {
        // No override: the cache is read under the first configured build id in the config.
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext<Context>())
        store.saveSponsorTransactionsEnabled(true)
        store.saveChainId("eip155:1")
        store.saveSponsoredChains(BUILD_PROJECT_ID, listOf(WalletChainPolicy.DEFAULT_CHAIN_ID))
        val config = WalletSdkConfig(
            "app", "client",
            zeroDevProjectId = BUILD_PROJECT_ID,
        )

        WalletProviderFactory.createDefault(config, store)

        assertEquals(WalletChainPolicy.DEFAULT_CHAIN_ID, store.loadChainId())
    }

    @Test
    fun syncSessionChain_sponsorshipOn_cacheExcludesCurrent_remapsAndSetsChain() = kotlinx.coroutines.test.runTest {
        val store = storeWithCache(true, "eip155:42161", listOf(WalletChainPolicy.DEFAULT_CHAIN_ID, "eip155:1"))
        val connector = connectorOnChain("eip155:42161")

        val applied = WalletProviderFactory.syncSessionChain(selectionOver(connector), store, BUILD_PROJECT_ID)

        assertEquals(WalletChainPolicy.DEFAULT_CHAIN_ID, applied)
        assertEquals(WalletChainPolicy.DEFAULT_CHAIN_ID, store.loadChainId())
        verify(connector, times(1)).setChain(WalletChainPolicy.DEFAULT_CHAIN_ID)
        verify(connector, never()).setChain("eip155:42161")
    }

    private fun selectionOver(connector: WalletConnector) = WalletProviderSelection(
        activeSigner = mock(),
        activeConnector = connector,
    )

    private fun connectorOnChain(chainId: String?): WalletConnector = mock {
        on { getIdentity() } doReturn chainId?.let { WalletIdentity("0xabc", it) }
    }

    @Test
    fun syncSessionChain_candidateOrder_requestedThenStoreThenIdentity() = kotlinx.coroutines.test.runTest {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext<Context>())
        store.saveSponsorTransactionsEnabled(false)
        val connector = connectorOnChain("eip155:1")
        val selection = selectionOver(connector)

        assertEquals(
            "eip155:84532",
            WalletProviderFactory.syncSessionChain(selection, store, BUILD_PROJECT_ID, requestedChainId = "eip155:84532"),
        )
        verify(connector, times(1)).setChain("eip155:84532")

        // No requested chain and nothing stored: the identity chain is the last candidate.
        assertEquals("eip155:1", WalletProviderFactory.syncSessionChain(selection, store, BUILD_PROJECT_ID))

        // A stored chain wins over the identity chain.
        store.saveChainId("eip155:42161")
        assertEquals("eip155:42161", WalletProviderFactory.syncSessionChain(selection, store, BUILD_PROJECT_ID))
    }

    @Test
    fun syncSessionChain_noCandidate_returnsNullWithoutSetChain() = kotlinx.coroutines.test.runTest {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext<Context>())
        val connector = connectorOnChain(null)

        val applied = WalletProviderFactory.syncSessionChain(selectionOver(connector), store, BUILD_PROJECT_ID)

        assertNull(applied)
        verify(connector, never()).setChain(any())
    }

    /**
     * A second caller reads its candidate inside the lock. The store is empty when both callers
     * start, so the second caller can only see the later store write if it reads after the first
     * caller releases the lock; reading before the lock would push the identity chain instead.
     */
    // runBlocking on real threads, not runTest: the check is that a second caller blocks on the
    // lock while the first is mid-setChain, which virtual time cannot observe.
    @Test
    fun syncSessionChain_concurrentCallers_lastWriterWins() = runBlocking {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext<Context>())
        store.saveSponsorTransactionsEnabled(false)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pushed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val connector = mock<WalletConnector> {
            on { getIdentity() } doReturn WalletIdentity("0xabc", "eip155:1")
            on { setChain(any()) } doAnswer {
                pushed += it.getArgument<String>(0)
                if (pushed.size == 1) {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                }
                Unit
            }
        }
        val selection = selectionOver(connector)

        val first = async(Dispatchers.Default) {
            WalletProviderFactory.syncSessionChain(selection, store, BUILD_PROJECT_ID, requestedChainId = "eip155:84532")
        }
        check(firstEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            WalletProviderFactory.syncSessionChain(selection, store, BUILD_PROJECT_ID)
        }
        // Give the second caller time to reach the lock; it must still be waiting. Only then does
        // the store change, while the first caller still holds the lock.
        kotlinx.coroutines.delay(100)
        assertEquals(listOf("eip155:84532"), pushed.toList())
        store.saveChainId("eip155:421614")
        releaseFirst.countDown()

        assertEquals("eip155:84532", first.await())
        assertEquals("eip155:421614", second.await())
        assertEquals(listOf("eip155:84532", "eip155:421614"), pushed.toList())
    }

    private companion object {
        const val BUILD_PROJECT_ID = "550e8400-e29b-41d4-a716-446655440000"
    }
}
