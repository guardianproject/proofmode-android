package org.witness.proofmode.plugins.wallet.infra.factory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.witness.proofmode.plugins.wallet.infra.api.WalletAuthClient
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNotInitializedException
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderId
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderSelection
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
import org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnector
import timber.log.Timber

object WalletProviderFactory {
    fun createDefault(
        config: WalletSdkConfig,
        sessionStore: WalletSessionStore,
    ): WalletProviderSelection {
        val restoredChainId = sessionStore.loadChainId() ?: config.defaultChainId
        val privyConfig = config.copy(defaultChainId = restoredChainId)
        val privyConnector = PrivyWalletConnector(privyConfig, sessionStore)
        val configResolver: (String) -> ZeroDevConfig = { chainId ->
            ZeroDevConfigResolver.resolveEffectiveConfig(
                chainId = chainId,
                walletSdkConfig = config,
                sessionStore = sessionStore,
            )
        }
        val smartAccountConnector = ZeroDevSmartAccountConnector(privyConnector, configResolver)
        return WalletProviderSelection(
            selectedProvider = WalletProviderId.ZERODEV,
            activeSigner = smartAccountConnector,
            activeConnector = smartAccountConnector,
            activeTransactionSender = smartAccountConnector,
        )
    }

    suspend fun refreshSponsorshipForCurrentChain(selection: WalletProviderSelection) {
        val connector = selection.activeConnector as? ZeroDevSmartAccountConnector ?: return
        val chainId = connector.privyConnector.getIdentity()?.chainId ?: return
        connector.setChain(chainId)
    }

    fun wireSponsorshipPrefRefresh(
        sessionStore: WalletSessionStore,
        selection: WalletProviderSelection,
        scope: CoroutineScope,
    ) {
        sessionStore.setOnSponsorshipPrefsChangedListener {
            scope.launch(Dispatchers.IO) {
                runCatching { refreshSponsorshipForCurrentChain(selection) }
                    .onFailure { Timber.w(it, "sponsorship pref refresh failed") }
            }
        }
    }

    fun privyConnector(selection: WalletProviderSelection): PrivyWalletConnector {
        val connector = selection.activeConnector
        return when (connector) {
            is PrivyWalletConnector -> connector
            is ZeroDevSmartAccountConnector -> connector.privyConnector
            else -> throw WalletNotInitializedException("Unknown connector type")
        }
    }

    fun authClient(selection: WalletProviderSelection): WalletAuthClient =
        privyConnector(selection)

    /**
     * Bootstraps Privy SDK on a background coroutine for cold-start session restore (addendum §A step 1).
     *
     * Sponsored smart-account re-initialization is **not** invoked here — when Privy auth restores and
     * emits [org.witness.proofmode.plugins.wallet.infra.model.WalletConnected], the
     * [ZeroDevSmartAccountConnector] state bridge (option b) attempts sponsored smart-account init without requiring
     * [org.witness.proofmode.plugins.wallet.infra.api.WalletConnector.connect] or
     * [org.witness.proofmode.plugins.wallet.infra.api.WalletConnector.setChain].
     */
    suspend fun restoreBackgroundSession(
        appContext: Context,
        selection: WalletProviderSelection,
        scope: CoroutineScope,
    ) {
        privyConnector(selection).ensurePrivyBackground(appContext, scope)
    }
}
