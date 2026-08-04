package org.witness.proofmode.plugins.lp.deeplink

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.wallet.FakeAndroidKeyStoreProvider
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.deeplink.ParamOutcome
import org.witness.proofmode.plugins.lp.deeplink.ParsedParam
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkParseResult
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderSelection
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
import org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnector

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
        val mockActive = mock<ZeroDevSmartAccountConnector>()
        val mockPrivy = mock<PrivyWalletConnector>()
        whenever(mockActive.privyConnector).thenReturn(mockPrivy)
        val selection = mock<WalletProviderSelection>()
        whenever(selection.activeConnector).thenReturn(mockActive)

        val result = WalletDeepLinkApplier.apply(context, parseResult, store, selection)

        assertTrue(result.rejected)
        assertEquals("eip155:1", store.loadChainId())
        verify(mockActive, never()).setChain(any())
        verify(mockPrivy, never()).setChain(any())
    }

    @Test
    fun apply_validFullUri_persistsSessionStore_loggedOutUsesPrivySetChain() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockActive = mock<ZeroDevSmartAccountConnector>()
        val mockPrivy = mock<PrivyWalletConnector>()
        whenever(mockActive.getIdentity()).thenReturn(null)
        whenever(mockActive.privyConnector).thenReturn(mockPrivy)
        val selection = mock<WalletProviderSelection>()
        whenever(selection.activeConnector).thenReturn(mockActive)
        val store = mock<WalletSessionStore>()

        val parseResult = WalletDeepLinkParseResult(
            chain = ParsedParam(ParamOutcome.VALID, "eip155:42161"),
            sponsor = ParsedParam(ParamOutcome.VALID, true),
            projectId = ParsedParam(ParamOutcome.VALID, "550e8400-e29b-41d4-a716-446655440000"),
            host = "wallet",
            isWalletRoute = true,
        )

        val result = WalletDeepLinkApplier.apply(context, parseResult, store, selection)

        assertEquals("eip155:42161", result.appliedChain)
        assertEquals(true, result.appliedSponsor)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.appliedProjectId)
        assertFalse(result.rejected)
        verify(store).saveChainId("eip155:42161")
        verify(store).saveSponsorTransactionsEnabled(true)
        verify(store).saveZeroDevProjectIdOverride("550e8400-e29b-41d4-a716-446655440000")
        verify(mockPrivy).setChain("eip155:42161")
        verify(mockActive, never()).setChain("eip155:42161")
    }

    @Test
    fun apply_validChain_loggedInUsesActiveConnectorSetChain() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockActive = mock<ZeroDevSmartAccountConnector>()
        whenever(mockActive.getIdentity()).thenReturn(
            WalletIdentity("0x1234567890abcdef1234567890abcdef12345678", "eip155:1"),
        )
        val selection = mock<WalletProviderSelection>()
        whenever(selection.activeConnector).thenReturn(mockActive)
        val store = mock<WalletSessionStore>()

        val parseResult = WalletDeepLinkParseResult(
            chain = ParsedParam(ParamOutcome.VALID, "eip155:42161"),
            sponsor = null,
            projectId = null,
            host = "wallet",
            isWalletRoute = true,
        )

        WalletDeepLinkApplier.apply(context, parseResult, store, selection)

        verify(store).saveChainId("eip155:42161")
        verify(mockActive).setChain("eip155:42161")
    }

    @Test
    fun apply_invalidSponsorSkipped_validChainStillApplied() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockActive = mock<ZeroDevSmartAccountConnector>()
        val mockPrivy = mock<PrivyWalletConnector>()
        whenever(mockActive.getIdentity()).thenReturn(null)
        whenever(mockActive.privyConnector).thenReturn(mockPrivy)
        val selection = mock<WalletProviderSelection>()
        whenever(selection.activeConnector).thenReturn(mockActive)
        val store = mock<WalletSessionStore>()

        val parseResult = WalletDeepLinkParseResult(
            chain = ParsedParam(ParamOutcome.VALID, "eip155:8453"),
            sponsor = ParsedParam(ParamOutcome.INVALID),
            projectId = null,
            host = "wallet",
            isWalletRoute = true,
        )

        val result = WalletDeepLinkApplier.apply(context, parseResult, store, selection)

        assertEquals("eip155:8453", result.appliedChain)
        assertNull(result.appliedSponsor)
        assertTrue(result.skipped.contains("sponsor:invalid_value"))
        verify(store).saveChainId("eip155:8453")
        verify(store, never()).saveSponsorTransactionsEnabled(any())
    }

    @Test
    fun apply_integration_loggedOut_persistsChainToRealSessionStore() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WalletSigningPlugin.configure(
            WalletSdkConfig(
                privyAppId = "test-app",
                privyAppClientId = "test-client",
                defaultChainId = "eip155:1",
            ),
        )
        WalletSigningPlugin.register(context)
        val store = requireNotNull(WalletSigningPlugin.sessionStore())

        val uri = Uri.parse(
            "proofmode://wallet?chain=eip155:42161&sponsor=true&projectId=550e8400-e29b-41d4-a716-446655440000",
        )
        val parseResult = WalletDeepLinkParser().parse(uri)
        val result = WalletDeepLinkApplier.apply(
            context,
            parseResult,
            store,
            WalletSigningPlugin.providerSelection,
        )

        assertEquals("eip155:42161", store.loadChainId())
        assertTrue(store.isSponsorTransactionsEnabled())
        assertEquals("550e8400-e29b-41d4-a716-446655440000", store.loadZeroDevProjectIdOverride())
        assertFalse(result.rejected)
    }

    @Test
    fun parseWalletDeepLink_delegatesToParser() {
        val uri = Uri.parse("proofmode://wallet?chain=eip155:42161")
        val result = LocationProtocolPlugin.parseWalletDeepLink(uri)
        assertTrue(result.isWalletRoute)
        assertEquals(ParamOutcome.VALID, result.chain?.outcome)
        assertEquals("eip155:42161", result.chain?.value)
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
        val result = LocationProtocolPlugin.applyWalletDeepLink(context, uri)

        assertEquals("eip155:42161", result.appliedChain)
        assertEquals(
            "eip155:42161",
            requireNotNull(WalletSigningPlugin.sessionStore()).loadChainId(),
        )
    }
}
