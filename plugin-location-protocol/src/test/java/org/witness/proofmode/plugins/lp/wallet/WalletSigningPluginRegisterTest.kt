package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSigningPluginRegisterTest {

    @Before
    fun setUp() {
        TestWalletStackReset.reset()
        FakeAndroidKeyStoreProvider.setup()
    }

    @Test
    fun register_leavesIsRegisteredFalse_whenCreateDefaultThrows() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        Mockito.mockConstruction(PrivyWalletConnector::class.java) { _, _ ->
            throw IllegalStateException("simulated createDefault failure")
        }.use {
            assertThrows(Exception::class.java) {
                WalletSigningPlugin.register(context)
            }
            assertFalse(WalletSigningPlugin.isRegistered())
        }
    }
}
