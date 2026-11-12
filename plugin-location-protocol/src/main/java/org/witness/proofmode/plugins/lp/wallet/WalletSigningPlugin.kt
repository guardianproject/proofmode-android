package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.witness.proofmode.plugin.ProofmodePlugin
import org.witness.proofmode.plugins.wallet.infra.api.WalletAuthClient
import org.witness.proofmode.plugins.wallet.infra.config.ZeroDevSponsorshipProbe
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderSelection
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

object WalletSigningPlugin : ProofmodePlugin {

    private var sdkConfig: WalletSdkConfig? = null
    private var sessionStore: WalletSessionStore? = null
    lateinit var providerSelection: WalletProviderSelection
        private set

    // Lazy so configure() in Application.onCreate builds no HTTP client.
    private val defaultSponsorshipProbe by lazy { ZeroDevSponsorshipProbe() }

    /** Replaced in tests so no request leaves the JVM. */
    @VisibleForTesting
    internal var sponsorshipProbe:
        suspend (projectId: String, chainIds: List<String>) -> List<String>? =
        { projectId, chainIds -> defaultSponsorshipProbe.probe(projectId, chainIds) }

    /**
     * Supply SDK credentials before calling [register]. Must be called from
     * Application.onCreate (or equivalent) before any Activity is created.
     */
    fun configure(config: WalletSdkConfig) {
        sdkConfig = config
    }

    override fun register(context: Context) {
        val config = checkNotNull(sdkConfig) { "WalletSigningPlugin.configure must run before register" }

        val store = WalletSessionStore(context.applicationContext)
        providerSelection = WalletProviderFactory.createDefault(config, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sessionStore = store
        WalletProviderFactory.wireSponsorshipPrefRefresh(
            store,
            providerSelection,
            scope,
            buildProjectId = buildDefaultZeroDevProjectId(),
        )
    }

    fun authClient(): WalletAuthClient =
        WalletProviderFactory.authClient(providerSelection)

    suspend fun restoreBackgroundSession(appContext: Context, scope: CoroutineScope) {
        WalletProviderFactory.restoreBackgroundSession(appContext, providerSelection, scope)
    }

    fun sessionStore(): WalletSessionStore? = sessionStore

    fun isRegistered(): Boolean = sessionStore != null

    /**
     * Build-time ZeroDev project ID (no runtime override). The id is shared by every chain.
     * Used by Wallet Settings UI for configureServerDefault-style focus prefill
     * (value is masked in settings UI).
     */
    fun buildDefaultZeroDevProjectId(): String =
        sdkConfig?.zeroDevProjectId.orEmpty()
}
