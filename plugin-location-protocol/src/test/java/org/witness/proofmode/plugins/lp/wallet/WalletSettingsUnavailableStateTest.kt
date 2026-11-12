package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationTimedOutException
import org.witness.proofmode.plugins.wallet.infra.model.WalletAuthenticating
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnecting
import org.witness.proofmode.plugins.wallet.infra.model.WalletCreatingWallet
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSettingsUnavailableStateTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
    }

    private class Row(
        val name: String,
        val state: WalletState,
        @StringRes val text: Int?,
        val connect: Int,
        val connectEnabled: Boolean,
        val disconnect: Int,
        val retry: Int,
        val copy: Int,
        val explorer: Int,
    )

    private val rows = listOf(
        Row(
            name = "Unavailable",
            state = WalletUnavailable(WalletCreationTimedOutException()),
            text = R.string.wallet_unavailable,
            connect = View.GONE,
            connectEnabled = true,
            disconnect = View.VISIBLE,
            retry = View.VISIBLE,
            copy = View.GONE,
            explorer = View.GONE,
        ),
        Row(
            name = "CreatingWallet",
            state = WalletCreatingWallet,
            text = R.string.wallet_creating,
            connect = View.VISIBLE,
            connectEnabled = false,
            disconnect = View.GONE,
            retry = View.GONE,
            copy = View.GONE,
            explorer = View.GONE,
        ),
        Row(
            name = "AwaitingNetwork",
            state = WalletAwaitingNetwork,
            text = R.string.wallet_awaiting_network,
            connect = View.GONE,
            connectEnabled = true,
            disconnect = View.VISIBLE,
            retry = View.GONE,
            copy = View.GONE,
            explorer = View.GONE,
        ),
        Row(
            name = "Disconnected",
            state = WalletDisconnected,
            text = R.string.wallet_not_connected,
            connect = View.VISIBLE,
            connectEnabled = true,
            disconnect = View.GONE,
            retry = View.GONE,
            copy = View.GONE,
            explorer = View.GONE,
        ),
        Row(
            name = "Connecting",
            state = WalletConnecting(),
            text = R.string.wallet_connecting,
            connect = View.VISIBLE,
            connectEnabled = false,
            disconnect = View.GONE,
            retry = View.GONE,
            copy = View.GONE,
            explorer = View.GONE,
        ),
        Row(
            name = "Authenticating",
            state = WalletAuthenticating,
            text = R.string.wallet_authenticating,
            connect = View.VISIBLE,
            connectEnabled = false,
            disconnect = View.GONE,
            retry = View.GONE,
            copy = View.GONE,
            explorer = View.GONE,
        ),
        Row(
            name = "Connected",
            state = WalletConnected(WalletIdentity("0x1234567890abcdef1234567890abcdef12345678", WalletChainPolicy.DEFAULT_CHAIN_ID)),
            text = null,
            connect = View.GONE,
            connectEnabled = true,
            disconnect = View.VISIBLE,
            retry = View.GONE,
            copy = View.VISIBLE,
            explorer = View.VISIBLE,
        ),
    )

    @Test
    fun walletState_rendersTextAndButtonVisibility() {
        for (row in rows) {
            TestWalletStackReset.reset()
            val activity = launchWalletSettingsWith(context, FakeWalletConnector(row.state))

            val expectedText = row.text?.let(activity::getString)
            val actualText = activity.findViewById<TextView>(R.id.tv_wallet_address).text.toString()
            if (expectedText != null) {
                assertEquals("${row.name} text", expectedText, actualText)
            } else {
                // Connected: abbreviated address, never the not-connected copy.
                assertEquals(
                    "${row.name} text",
                    activity.getString(R.string.wallet_address_connected, "0x1234…5678"),
                    actualText,
                )
            }
            val connect = activity.findViewById<Button>(R.id.btn_connect_wallet)
            assertEquals("${row.name} connect", row.connect, connect.visibility)
            assertEquals("${row.name} connect enabled", row.connectEnabled, connect.isEnabled)
            assertEquals("${row.name} disconnect", row.disconnect, activity.findViewById<Button>(R.id.btn_disconnect_wallet).visibility)
            assertEquals("${row.name} retry", row.retry, activity.findViewById<Button>(R.id.btn_retry_wallet).visibility)
            assertEquals("${row.name} copy", row.copy, activity.findViewById<ImageButton>(R.id.btn_copy_address).visibility)
            assertEquals("${row.name} explorer", row.explorer, activity.findViewById<MaterialCardView>(R.id.card_explorer_links).visibility)
        }
    }
}
