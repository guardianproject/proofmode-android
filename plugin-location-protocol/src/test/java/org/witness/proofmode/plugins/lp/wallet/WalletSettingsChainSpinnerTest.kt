package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSettingsChainSpinnerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
    }

    private fun launch(): WalletSettingsActivity {
        val intent = Intent(context, WalletSettingsActivity::class.java)
        return Robolectric.buildActivity(WalletSettingsActivity::class.java, intent)
            .create().start().visible().get()
    }

    private fun ensureEffectiveProjectId() {
        val store = WalletSigningPlugin.sessionStore()
        val effective = ZeroDevConfigResolver.effectiveProjectId(
            sessionOverride = store?.loadZeroDevProjectIdOverride(),
            buildProjectId = WalletSigningPlugin.buildDefaultZeroDevProjectId(),
        )
        if (!ZeroDevConfigResolver.isConfiguredSecret(effective)) {
            store?.saveZeroDevProjectIdOverride("550e8400-e29b-41d4-a716-446655440000")
        }
    }

    @Test
    fun sponsorshipOn_withProjectConfig_spinnerShowsTwoTestnets_defaultSepolia() {
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        WalletSigningPlugin.sessionStore()?.saveSponsorTransactionsEnabled(true)
        ensureEffectiveProjectId()

        val activity = launch()
        val spinner = activity.findViewById<Spinner>(R.id.spinner_chain)
        assertEquals(2, spinner.adapter.count)
        assertEquals("Sepolia Testnet", spinner.adapter.getItem(0))
        assertEquals("Arbitrum Sepolia", spinner.adapter.getItem(1))
        assertEquals(0, spinner.selectedItemPosition)
        assertTrue(spinner.isEnabled)
        assertEquals(
            View.GONE,
            activity.findViewById<TextView>(R.id.tv_empty_sponsored_networks).visibility,
        )
    }

    @Test
    fun sponsorshipOff_spinnerShowsFiveCatalogRows() {
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        WalletSigningPlugin.sessionStore()?.saveSponsorTransactionsEnabled(false)
        WalletSigningPlugin.sessionStore()?.saveChainId("eip155:1")

        val activity = launch()
        val spinner = activity.findViewById<Spinner>(R.id.spinner_chain)
        assertEquals(5, spinner.adapter.count)
        assertEquals("Ethereum Mainnet", spinner.adapter.getItem(0))
        assertTrue(spinner.isEnabled)
    }

    @Test
    fun sponsorshipOn_noEffectiveProjectId_graysOutSpinnerAndShowsNote() {
        WalletSigningPlugin.configure(
            WalletSdkConfig(privyAppId = "app", privyAppClientId = "client"),
        )
        WalletSigningPlugin.register(context)
        WalletSigningPlugin.sessionStore()?.saveSponsorTransactionsEnabled(true)
        WalletSigningPlugin.sessionStore()?.saveZeroDevProjectIdOverride(null)

        val activity = launch()
        val spinner = activity.findViewById<Spinner>(R.id.spinner_chain)
        assertEquals(0, spinner.adapter.count)
        assertFalse(spinner.isEnabled)
        val note = activity.findViewById<TextView>(R.id.tv_empty_sponsored_networks)
        assertEquals(View.VISIBLE, note.visibility)
        assertEquals(
            activity.getString(R.string.wallet_empty_sponsored_networks),
            note.text.toString(),
        )
    }

    @Test
    fun noSavedChain_selectsSepoliaNotEthereum() {
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        WalletSigningPlugin.sessionStore()?.clear()
        WalletSigningPlugin.sessionStore()?.saveSponsorTransactionsEnabled(false)

        val activity = launch()
        val spinner = activity.findViewById<Spinner>(R.id.spinner_chain)
        assertEquals("Sepolia Testnet", spinner.selectedItem)
    }

    @Test
    fun saveProjectId_validOverride_clearsEmptySponsoredGrayOut() {
        WalletSigningPlugin.configure(
            WalletSdkConfig(
                privyAppId = "app",
                privyAppClientId = "client",
                zeroDevConfigs = mapOf(
                    "eip155:11155111" to org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig(
                        projectId = "replace-me-placeholder",
                        bundlerUrl = "https://bundler.example",
                        paymasterUrl = "https://paymaster.example",
                    ),
                ),
            ),
        )
        WalletSigningPlugin.register(context)
        WalletSigningPlugin.sessionStore()?.saveSponsorTransactionsEnabled(true)
        WalletSigningPlugin.sessionStore()?.saveZeroDevProjectIdOverride(null)
        WalletSigningPlugin.sessionStore()?.saveChainId("eip155:11155111")

        val activity = launch()
        val spinner = activity.findViewById<Spinner>(R.id.spinner_chain)
        assertEquals(0, spinner.adapter.count)
        assertFalse(spinner.isEnabled)

        activity.findViewById<android.widget.EditText>(R.id.et_zerodev_project_id)
            .setText("550e8400-e29b-41d4-a716-446655440000")
        activity.findViewById<android.widget.Button>(R.id.btn_save_zerodev_project_id).performClick()

        assertEquals(2, spinner.adapter.count)
        assertTrue(spinner.isEnabled)
        assertEquals(
            View.GONE,
            activity.findViewById<TextView>(R.id.tv_empty_sponsored_networks).visibility,
        )
    }
}
