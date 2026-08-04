package org.witness.proofmode.plugins.lp.wallet

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkContract
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSettingsDeepLinkExtrasTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
        WalletSettingsActivity.lastDeepLinkRejectMessageForTests = null
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
    }

    @Test
    fun handleDeepLinkExtras_withRejectedExtra_recordsRejectMessage() {
        val intent = Intent(context, WalletSettingsActivity::class.java)
        val controller = Robolectric.buildActivity(WalletSettingsActivity::class.java, intent)
        controller.create().start().visible()

        val rejectIntent = Intent().apply {
            putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, true)
            putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE, "Chain rejected")
        }
        controller.get().handleDeepLinkExtras(rejectIntent)

        assertEquals("Chain rejected", WalletSettingsActivity.lastDeepLinkRejectMessageForTests)
    }

    @Test
    fun onNewIntent_withAppliedChainExtra_refreshesSpinnerFromSessionStore() {
        val store = WalletSigningPlugin.sessionStore()!!
        store.saveChainId("eip155:42161")

        val intent = Intent(context, WalletSettingsActivity::class.java)
        val controller = Robolectric.buildActivity(WalletSettingsActivity::class.java, intent)
        controller.create().start().visible()

        val refreshIntent = Intent().apply {
            putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, false)
            putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_APPLIED_CHAIN, "eip155:42161")
            putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE, "Updated")
        }
        controller.newIntent(refreshIntent)

        val spinner = controller.get().findViewById<android.widget.Spinner>(R.id.spinner_chain)
        val arbitrumIndex = SUPPORTED_CHAINS.indexOfFirst { it.caip2Id == "eip155:42161" }
        assertEquals(arbitrumIndex, spinner.selectedItemPosition)
        assertNull(WalletSettingsActivity.lastDeepLinkRejectMessageForTests)
    }
}
