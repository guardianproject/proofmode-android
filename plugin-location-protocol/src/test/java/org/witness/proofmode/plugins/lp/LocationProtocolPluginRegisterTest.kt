package org.witness.proofmode.plugins.lp

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.wallet.FakeAndroidKeyStoreProvider
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationProtocolPluginRegisterTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
    }

    @Test
    fun register_autoConfiguresWalletSigningWithoutCallerConfig() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Mirror LocationProtocolPlugin.register() wallet bootstrap without Flutter engine init
        // (native libs unavailable in Robolectric).
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        assertNotNull(WalletSigningPlugin.providerSelection.activeConnector)
    }
}
