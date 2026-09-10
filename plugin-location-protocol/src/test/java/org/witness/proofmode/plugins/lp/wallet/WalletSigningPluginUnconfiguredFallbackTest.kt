package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSigningPluginUnconfiguredFallbackTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
    }

    @Test
    fun register_withoutConfigure_usesSepoliaDefaultChainId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WalletSigningPlugin.register(context)
        val privy = WalletProviderFactory.privyConnector(WalletSigningPlugin.providerSelection)
        val selected = privy.javaClass.getDeclaredField("selectedChainId").apply {
            isAccessible = true
        }.get(privy) as String
        assertEquals(WalletChainPolicy.DEFAULT_CHAIN_ID, selected)
    }
}
