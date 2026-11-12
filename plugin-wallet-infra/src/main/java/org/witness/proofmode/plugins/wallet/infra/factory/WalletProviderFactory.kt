package org.witness.proofmode.plugins.wallet.infra.factory

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.witness.proofmode.plugins.wallet.infra.api.WalletAuthClient
import org.witness.proofmode.plugins.wallet.infra.config.WalletChainPolicy
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevConfigResolver
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNotInitializedException
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
        val stored = sessionStore.loadChainId()
        val sessionChainId = WalletChainPolicy.resolveSessionChainId(
            storedChainId = stored,
            sponsorshipOn = sessionStore.isSponsorTransactionsEnabled(),
            // The build project id keys the cache; a project override wins inside the helper.
            sponsoredChains = ZeroDevConfigResolver.cachedSponsoredChains(
                sessionStore,
                config.zeroDevProjectId,
            ),
            defaultChainId = config.defaultChainId,
        )
        if (stored != null && sessionChainId != stored) {
            sessionStore.saveChainId(sessionChainId)
        }
        val privyConfig = config.copy(defaultChainId = sessionChainId)
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
            activeSigner = smartAccountConnector,
            activeConnector = smartAccountConnector,
            activeTransactionSender = smartAccountConnector,
        )
    }

    // Every chain push to the connector goes through here, one at a time. The candidate is read
    // inside the lock, so a caller that waited sees what earlier callers and the deep-link
    // applier wrote to the store, and cannot push a stale chain last.
    private val syncMutex = Mutex()

    /**
     * Pushes the resolved session chain to the active connector every time; the connector decides
     * whether the push costs anything.
     *
     * Candidate: [requestedChainId], else the stored chain, else the connector's identity chain.
     * With sponsorship on, a candidate outside the cached sponsored set for the effective project
     * id ([buildProjectId] unless overridden) is remapped by [WalletChainPolicy]. Returns the
     * applied chain id, or null when there is no candidate.
     */
    suspend fun syncSessionChain(
        selection: WalletProviderSelection,
        sessionStore: WalletSessionStore,
        buildProjectId: String,
        requestedChainId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        // The lock is taken and released on IO. A holder that resumed on the main thread to
        // release it would keep every other caller waiting for the main looper.
        syncMutex.withLock {
            val connector = selection.activeConnector
            val candidate = requestedChainId
                ?: sessionStore.loadChainId()
                ?: connector.getIdentity()?.chainId
                ?: return@withLock null
            val chainId = WalletChainPolicy.resolveSessionChainId(
                storedChainId = candidate,
                sponsorshipOn = sessionStore.isSponsorTransactionsEnabled(),
                sponsoredChains = ZeroDevConfigResolver.cachedSponsoredChains(sessionStore, buildProjectId),
            )
            if (chainId != candidate) {
                sessionStore.saveChainId(chainId)
            }
            connector.setChain(chainId)
            chainId
        }
    }

    fun wireSponsorshipPrefRefresh(
        sessionStore: WalletSessionStore,
        selection: WalletProviderSelection,
        scope: CoroutineScope,
        buildProjectId: String,
    ) {
        sessionStore.setOnSponsorshipPrefsChangedListener {
            scope.launch(Dispatchers.IO) {
                runCatching { syncSessionChain(selection, sessionStore, buildProjectId) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Timber.w(it, "sponsorship pref refresh failed")
                    }
            }
        }
    }

    fun privyConnector(selection: WalletProviderSelection): PrivyWalletConnector {
        val connector = selection.activeConnector as? ZeroDevSmartAccountConnector
            ?: throw WalletNotInitializedException("Unknown connector type")
        return connector.privyConnector
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
