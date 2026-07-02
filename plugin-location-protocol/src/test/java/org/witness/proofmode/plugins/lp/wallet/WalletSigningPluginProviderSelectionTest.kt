package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderId
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnector

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSigningPluginProviderSelectionTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
    }

    @Test
    fun `register defaults provider selection to ZERODEV`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WalletSigningPlugin.configure(
            WalletSdkConfig(
                privyAppId = "test-app-id",
                privyAppClientId = "test-client-id",
                defaultChainId = "eip155:1",
            ),
        )

        WalletSigningPlugin.register(context)

        val selection = WalletSigningPlugin.providerSelection
        assertEquals(WalletProviderId.ZERODEV, selection.selectedProvider)
        assertTrue(selection.activeConnector is ZeroDevSmartAccountConnector)
        assertTrue(selection.activeSigner is ZeroDevSmartAccountConnector)
        assertSame(selection.activeConnector, selection.activeSigner)

        val smartConnector = selection.activeConnector as ZeroDevSmartAccountConnector
        assertTrue(smartConnector.privyConnector is PrivyWalletConnector)
    }

    @Test
    fun `register initializes active connector and signer from ZeroDev implementation wrapping Privy`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WalletSigningPlugin.configure(
            WalletSdkConfig(
                privyAppId = "another-app-id",
                privyAppClientId = "another-client-id",
                defaultChainId = "eip155:1",
            ),
        )

        WalletSigningPlugin.register(context)

        val selection = WalletSigningPlugin.providerSelection
        val activeConnector = selection.activeConnector as? ZeroDevSmartAccountConnector
        val activeSigner = selection.activeSigner as? ZeroDevSmartAccountConnector

        assertTrue(activeConnector != null)
        assertTrue(activeSigner != null)
        assertSame(activeConnector, activeSigner)
        assertTrue(activeConnector!!.privyConnector is PrivyWalletConnector)
    }
}
