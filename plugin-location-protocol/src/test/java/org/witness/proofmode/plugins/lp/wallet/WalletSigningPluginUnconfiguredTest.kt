package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.TestWalletStackReset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSigningPluginUnconfiguredTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
    }

    @Test
    fun register_withoutConfigure_throws() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThrows(IllegalStateException::class.java) { WalletSigningPlugin.register(context) }
        assertFalse(WalletSigningPlugin.isRegistered())
    }
}
