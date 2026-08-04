package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.witness.proofmode.plugin.ProofmodePlugin
import org.witness.proofmode.plugins.wallet.infra.api.WalletAuthClient
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderSelection
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnector

object WalletSigningPlugin : ProofmodePlugin {

    private var sdkConfig: WalletSdkConfig? = null
    private var sessionStore: WalletSessionStore? = null
    lateinit var providerSelection: WalletProviderSelection
        private set

    /**
     * Supply SDK credentials before calling [register]. Must be called from
     * Application.onCreate (or equivalent) before any Activity is created.
     */
    fun configure(config: WalletSdkConfig) {
        sdkConfig = config
    }

    override fun register(context: Context) {
        val config = sdkConfig ?: WalletSdkConfig(
            privyAppId = "",
            privyAppClientId = "",
            defaultChainId = "eip155:1",
        )

        val store = WalletSessionStore(context.applicationContext)
        providerSelection = WalletProviderFactory.createDefault(config, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        WalletProviderFactory.wireSponsorshipPrefRefresh(store, providerSelection, scope)
        sessionStore = store
    }

    fun authClient(): WalletAuthClient =
        WalletProviderFactory.authClient(providerSelection)

    suspend fun restoreBackgroundSession(appContext: Context, scope: CoroutineScope) {
        WalletProviderFactory.restoreBackgroundSession(appContext, providerSelection, scope)
    }

    fun sessionStore(): WalletSessionStore? = sessionStore

    fun isRegistered(): Boolean = sessionStore != null

    /**
     * Build-time ZeroDev project ID for the active chain (no runtime override).
     * Used by Wallet Settings UI for helper text and configureServerDefault-style prefill.
     */
    fun buildDefaultZeroDevProjectId(): String {
        val config = sdkConfig ?: return ""
        val chainId = when {
            ::providerSelection.isInitialized -> {
                (providerSelection.activeConnector as? ZeroDevSmartAccountConnector)
                    ?.privyConnector?.getIdentity()?.chainId
            }
            else -> null
        } ?: sessionStore?.loadChainId()
            ?: config.zeroDevConfigs.keys.firstOrNull()
            ?: return ""
        return config.zeroDevConfigs[chainId]?.projectId.orEmpty()
    }
}
