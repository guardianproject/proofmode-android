package org.witness.proofmode.plugins.lp

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.wallet.FakeAndroidKeyStoreProvider
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationProtocolPluginWalletFacadeTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
    }

    @Test
    fun isWalletStackRegistered_falseBeforeRegister() {
        assertFalse(LocationProtocolPlugin.isWalletStackRegistered())
    }

    @Test
    fun isWalletStackRegistered_trueAfterWalletSigningPluginRegister() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        assertTrue(LocationProtocolPlugin.isWalletStackRegistered())
    }

    @Test
    fun bindWalletActivity_whenStackNotRegistered_doesNotThrow() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        LocationProtocolPlugin.bindWalletActivity(activity)
    }

    @Test
    fun unbindWalletActivity_whenStackNotRegistered_doesNotThrow() {
        LocationProtocolPlugin.unbindWalletActivity()
    }

    @Test
    fun walletDiagnostics_whenStackNotRegistered_returnsDisconnectedSnapshot() {
        val diag = LocationProtocolPlugin.walletDiagnostics()
        assertFalse(diag.connected)
        assertEquals("unregistered", diag.connectorName)
    }

    @Test
    fun walletDiagnostics_returnsSnapshotAfterRegister() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        val diag = LocationProtocolPlugin.walletDiagnostics()
        assertNotNull(diag.connectorName)
    }
}
