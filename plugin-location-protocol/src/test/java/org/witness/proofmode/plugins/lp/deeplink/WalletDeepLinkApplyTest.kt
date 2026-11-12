package org.witness.proofmode.plugins.lp.deeplink

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.FakeAndroidKeyStoreProvider
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.deeplink.ParamOutcome
import org.witness.proofmode.plugins.lp.deeplink.ParsedParam
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkParseResult
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderSelection
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletDeepLinkApplyTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
    }

    @Test
    fun apply_rejectsInvalidChainWithoutPersisting() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = WalletSessionStore(context)
        store.saveChainId("eip155:1")

        val parseResult = WalletDeepLinkParseResult(
            chain = ParsedParam(ParamOutcome.INVALID),
            sponsor = null,
            projectId = null,
            host = "wallet",
            isWalletRoute = true,
        )
        val mockActive = mock<WalletConnector>()
        val selection = mock<WalletProviderSelection>()
        whenever(selection.activeConnector).thenReturn(mockActive)

        val result = WalletDeepLinkApplier.apply(context, parseResult, store, selection)

        assertTrue(result.rejected)
        assertEquals("eip155:1", store.loadChainId())
        verify(mockActive, never()).setChain(any())
    }

    private val projectA = "550e8400-e29b-41d4-a716-446655440000"
    private val projectB = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    private val arbitrumSepolia = "eip155:421614"

    private fun cachedStore(
        sponsorOn: Boolean,
        cache: List<String>?,
        storedChain: String? = null,
    ): WalletSessionStore {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext<android.content.Context>())
        store.saveSponsorTransactionsEnabled(sponsorOn)
        store.saveZeroDevProjectIdOverride(projectA)
        cache?.let { store.saveSponsoredChains(projectA, it) }
        storedChain?.let(store::saveChainId)
        return store
    }

    private fun privySelection(): Pair<PrivyWalletConnector, WalletProviderSelection> {
        val privy = mock<PrivyWalletConnector>()
        whenever(privy.getIdentity()).thenReturn(null)
        val selection = mock<WalletProviderSelection>()
        whenever(selection.activeConnector).thenReturn(privy)
        return privy to selection
    }

    private fun chainLink(chain: String, sponsor: Boolean? = null, projectId: String? = null) =
        WalletDeepLinkParseResult(
            chain = ParsedParam(ParamOutcome.VALID, chain),
            sponsor = sponsor?.let { ParsedParam(ParamOutcome.VALID, it) },
            projectId = projectId?.let { ParsedParam(ParamOutcome.VALID, it) },
            host = "wallet",
            isWalletRoute = true,
        )

    @Test
    fun apply_chainOutsideCachedSet_remapsAndNeverSyncsGivenChain() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = cachedStore(sponsorOn = false, cache = listOf(WalletChainPolicy.DEFAULT_CHAIN_ID, arbitrumSepolia), storedChain = "eip155:8453")
        val (privy, selection) = privySelection()

        WalletDeepLinkApplier.apply(
            context, chainLink("eip155:1", sponsor = true), store, selection,
        )

        assertEquals(WalletChainPolicy.DEFAULT_CHAIN_ID, store.loadChainId())
        assertTrue(store.isSponsorTransactionsEnabled())
        verify(privy, times(1)).setChain(any())
        verify(privy).setChain(WalletChainPolicy.DEFAULT_CHAIN_ID)
    }

    @Test
    fun apply_sponsorOnlyLink_storedChainOutsideCache_syncsRewrittenChain() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = cachedStore(
            sponsorOn = false,
            cache = listOf(WalletChainPolicy.DEFAULT_CHAIN_ID),
            storedChain = "eip155:8453",
        )
        val (privy, selection) = privySelection()

        WalletDeepLinkApplier.apply(
            context,
            WalletDeepLinkParseResult(
                chain = null,
                sponsor = ParsedParam(ParamOutcome.VALID, true),
                projectId = null,
                host = "wallet",
                isWalletRoute = true,
            ),
            store,
            selection,
        )

        assertEquals(WalletChainPolicy.DEFAULT_CHAIN_ID, store.loadChainId())
        verify(privy, times(1)).setChain(any())
        verify(privy).setChain(WalletChainPolicy.DEFAULT_CHAIN_ID)
    }

    @Test
    fun apply_persistedParamIsReadBySync_projectIdAndSponsorOff() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Both rows start from project A's cache [DEFAULT_CHAIN_ID], sponsorship on, and a link chain
        // outside it. Each row sets one param, so each catches a different mutation: the sync running
        // before that param is persisted. One link must not carry both params; one would hide the other.
        // (label, link, assertions only this row makes)
        class Row(
            val label: String,
            val link: WalletDeepLinkParseResult,
            val extraAsserts: (WalletSessionStore, PrivyWalletConnector) -> Unit,
        )
        listOf(
            // The link switches to project B, which has no cache, so the chain stays.
            Row("projectId=B", chainLink("eip155:42161", projectId = projectB)) { store, _ ->
                assertEquals("case: projectId=B", projectB, store.loadZeroDevProjectIdOverride())
            },
            // The store starts with sponsorship on; the link turns it off, so the sync must read the flag
            // the link just wrote and not the one the store held before.
            Row("sponsor=false", chainLink("eip155:42161", sponsor = false)) { _, privy ->
                verify(privy, times(1)).setChain(any())
            },
        ).forEach { row ->
            val store = cachedStore(sponsorOn = true, cache = listOf(WalletChainPolicy.DEFAULT_CHAIN_ID))
            val (privy, selection) = privySelection()

            WalletDeepLinkApplier.apply(context, row.link, store, selection)

            assertEquals("case: ${row.label}", "eip155:42161", store.loadChainId())
            verify(privy).setChain("eip155:42161")
            row.extraAsserts(store, privy)
        }
    }

    @Test
    fun apply_validFullUri_persistsSessionStore_pushesLinkChainOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = WalletSessionStore(context)
        store.saveSponsorTransactionsEnabled(false)
        val mockPrivy = mock<PrivyWalletConnector>()
        whenever(mockPrivy.getIdentity()).thenReturn(null)
        val selection = mock<WalletProviderSelection>()
        whenever(selection.activeConnector).thenReturn(mockPrivy)

        val parseResult = WalletDeepLinkParseResult(
            chain = ParsedParam(ParamOutcome.VALID, "eip155:42161"),
            sponsor = ParsedParam(ParamOutcome.VALID, true),
            projectId = ParsedParam(ParamOutcome.VALID, "550e8400-e29b-41d4-a716-446655440000"),
            host = "wallet",
            isWalletRoute = true,
        )

        val result = WalletDeepLinkApplier.apply(context, parseResult, store, selection)

        assertFalse(result.rejected)
        assertEquals("eip155:42161", store.loadChainId())
        assertTrue(store.isSponsorTransactionsEnabled())
        assertEquals("550e8400-e29b-41d4-a716-446655440000", store.loadZeroDevProjectIdOverride())
        verify(mockPrivy, times(1)).setChain(any())
        verify(mockPrivy).setChain("eip155:42161")
    }

    /**
     * The sponsor save fires the prefs listener while the link is still being applied. The
     * listener's sync must not push the connector's old identity chain after the link's chain.
     */
    @Test
    fun apply_chainAndSponsorParams_prefsListenerWired_finalPushedChainIsLinkChain() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = WalletSessionStore(context)
        store.saveSponsorTransactionsEnabled(true)
        store.saveChainId("eip155:11155111")
        val pushed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val connector = mock<WalletConnector> {
            on { getIdentity() } doReturn WalletIdentity("0xabc", "eip155:11155111")
            on { setChain(any()) } doAnswer { pushed += it.getArgument<String>(0); Unit }
        }
        val selection = WalletProviderSelection(
            activeSigner = mock(),
            activeConnector = connector,
        )
        val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        WalletProviderFactory.wireSponsorshipPrefRefresh(store, selection, listenerScope, buildProjectId = "")

        WalletDeepLinkApplier.apply(
            context,
            WalletDeepLinkParseResult(
                chain = ParsedParam(ParamOutcome.VALID, "eip155:84532"),
                sponsor = ParsedParam(ParamOutcome.VALID, false),
                projectId = null,
                host = "wallet",
                isWalletRoute = true,
            ),
            store,
            selection,
        )
        listenerScope.coroutineContext.job.children.toList().forEach { it.join() }

        assertEquals("eip155:84532", store.loadChainId())
        assertTrue("listener pushed", pushed.size >= 2)
        assertTrue(pushed.all { it == "eip155:84532" })
    }

    @Test
    fun apply_invalidSponsorSkipped_validChainStillApplied() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockPrivy = mock<PrivyWalletConnector>()
        whenever(mockPrivy.getIdentity()).thenReturn(null)
        val selection = mock<WalletProviderSelection>()
        whenever(selection.activeConnector).thenReturn(mockPrivy)
        val store = mock<WalletSessionStore>()
        whenever(store.isSponsorTransactionsEnabled()).thenReturn(false)

        val parseResult = WalletDeepLinkParseResult(
            chain = ParsedParam(ParamOutcome.VALID, "eip155:8453"),
            sponsor = ParsedParam(ParamOutcome.INVALID),
            projectId = null,
            host = "wallet",
            isWalletRoute = true,
        )

        val result = WalletDeepLinkApplier.apply(context, parseResult, store, selection)

        assertEquals(context.getString(R.string.wallet_deep_link_partial_apply), result.userMessage)
        verify(store).saveChainId("eip155:8453")
        verify(store, never()).saveSponsorTransactionsEnabled(any())
    }

    @Test
    fun applyWalletDeepLink_delegatesToApplier() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WalletSigningPlugin.configure(
            WalletSdkConfig(
                privyAppId = "test-app",
                privyAppClientId = "test-client",
                defaultChainId = "eip155:1",
            ),
        )
        WalletSigningPlugin.register(context)

        val uri = Uri.parse("proofmode://wallet?chain=eip155:42161")
        LocationProtocolPlugin.applyWalletDeepLink(context, uri)

        assertEquals(
            "eip155:42161",
            requireNotNull(WalletSigningPlugin.sessionStore()).loadChainId(),
        )
    }
}
