package org.witness.proofmode.plugins.lp.wallet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationTimedOutException
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletCreatingWallet
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSettingsUnavailableStateTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class FakeConnector(initial: WalletState) : WalletConnector {
        val state = MutableStateFlow(initial)
        override val stateFlow: StateFlow<WalletState> = state
        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override fun getIdentity(): WalletIdentity? = null
        override fun setChain(chainId: String) {}
        override fun bindActivity(activity: Activity) {}
        override fun unbindActivity() {}
    }

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
    }

    private fun launchWith(connector: FakeConnector): WalletSettingsActivity {
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        val real = WalletSigningPlugin.providerSelection
        val field = WalletSigningPlugin::class.java.getDeclaredField("providerSelection")
        field.isAccessible = true
        field.set(WalletSigningPlugin, real.copy(activeConnector = connector))
        val intent = Intent(context, WalletSettingsActivity::class.java)
        return Robolectric.buildActivity(WalletSettingsActivity::class.java, intent)
            .create().start().visible().get()
    }

    @Test
    fun walletUnavailable_showsRetryAndUnavailableText() {
        val activity = launchWith(FakeConnector(WalletUnavailable(WalletCreationTimedOutException())))

        assertEquals(
            activity.getString(R.string.wallet_unavailable),
            activity.findViewById<TextView>(R.id.tv_wallet_address).text.toString(),
        )
        assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.btn_retry_wallet).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.btn_disconnect_wallet).visibility)
        assertEquals(View.GONE, activity.findViewById<Button>(R.id.btn_connect_wallet).visibility)
    }

    @Test
    fun walletCreating_showsCreatingTextWithoutRetry() {
        val activity = launchWith(FakeConnector(WalletCreatingWallet))

        assertEquals(
            activity.getString(R.string.wallet_creating),
            activity.findViewById<TextView>(R.id.tv_wallet_address).text.toString(),
        )
        assertEquals(View.GONE, activity.findViewById<Button>(R.id.btn_retry_wallet).visibility)
    }

    @Test
    fun `awaiting network shows waiting text and disconnect only`() {
        val activity = launchWith(FakeConnector(WalletAwaitingNetwork))

        assertEquals(
            "Waiting for network…",
            activity.findViewById<TextView>(R.id.tv_wallet_address).text.toString(),
        )
        assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.btn_disconnect_wallet).visibility)
        assertEquals(View.GONE, activity.findViewById<Button>(R.id.btn_connect_wallet).visibility)
        assertEquals(View.GONE, activity.findViewById<Button>(R.id.btn_retry_wallet).visibility)
        assertEquals(View.GONE, activity.findViewById<ImageButton>(R.id.btn_copy_address).visibility)
        assertEquals(View.GONE, activity.findViewById<MaterialCardView>(R.id.card_explorer_links).visibility)
    }
}
