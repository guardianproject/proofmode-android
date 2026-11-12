package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import android.content.Intent
import org.robolectric.Robolectric
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

/** Swaps [connector] in as the registered wallet stack's active connector. */
fun installActiveConnector(connector: WalletConnector) {
    val current = WalletSigningPlugin.providerSelection
    val field = WalletSigningPlugin::class.java.getDeclaredField("providerSelection")
    field.isAccessible = true
    field.set(WalletSigningPlugin, current.copy(activeConnector = connector))
}

/** Registers the wallet stack with [connector] as the active one, then starts the settings screen. */
fun launchWalletSettingsWith(context: Context, connector: FakeWalletConnector): WalletSettingsActivity {
    WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
    WalletSigningPlugin.register(context)
    installActiveConnector(connector)
    val intent = Intent(context, WalletSettingsActivity::class.java)
    return Robolectric.buildActivity(WalletSettingsActivity::class.java, intent)
        .create().start().visible().get()
}
