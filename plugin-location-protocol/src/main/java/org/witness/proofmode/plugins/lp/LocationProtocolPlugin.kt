package org.witness.proofmode.plugins.lp

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.annotation.VisibleForTesting
import android.net.Uri
import org.witness.proofmode.plugins.lp.attestation.EASAttestationManager
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationCoordinator
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkApplier
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkParseResult
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkParser
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkResult
import kotlinx.coroutines.CoroutineScope
import org.witness.proofmode.plugin.ProofmodePlugin
import org.witness.proofmode.plugins.lp.bridge.FlutterEngineProvider
import org.witness.proofmode.plugins.lp.bridge.LPBridgeMessenger
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
import org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnector
import org.witness.proofmode.plugins.lp.wallet.WalletDiagnostics
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Entry-point for the Location Protocol plugin module.
 *
 * Phase 2 adds [createCoordinator] as the public factory for callers (e.g. ShareProofActivity)
 * to obtain a [LocationProtocolAttestationCoordinator] without coupling to internal class names.
 *
 * Phase 3 will initialise the Flutter engine inside [register].
 */
object LocationProtocolPlugin : ProofmodePlugin {

    private var applicationScope: CoroutineScope? = null

    /** Call from Application.onCreate before any attestOnchain usage. */
    fun registerApplicationScope(scope: CoroutineScope) {
        applicationScope = scope
    }

    fun requireApplicationScope(): CoroutineScope =
        requireNotNull(applicationScope) {
            "LocationProtocolPlugin.registerApplicationScope() must be called from Application.onCreate"
        }

    private val registerWalletStackInvocationCountForTests = AtomicInteger(0)

    @Volatile
    private var registerWalletStackOnMainThreadForTests: Boolean? = null

    @VisibleForTesting
    fun getRegisterWalletStackInvocationCountForTests(): Int =
        registerWalletStackInvocationCountForTests.get()

    @VisibleForTesting
    fun wasRegisterWalletStackInvokedOnMainThreadForTests(): Boolean? =
        registerWalletStackOnMainThreadForTests

    @VisibleForTesting
    fun resetRegisterWalletStackInvocationCountForTests() {
        registerWalletStackInvocationCountForTests.set(0)
        registerWalletStackOnMainThreadForTests = null
    }

    /** IO-safe: Keystore / EncryptedSharedPreferences / connectors. Do not call from callers that import wallet-infra. */
    fun registerWalletStack(context: Context) {
        registerWalletStackInvocationCountForTests.incrementAndGet()
        registerWalletStackOnMainThreadForTests = Looper.getMainLooper().isCurrentThread
        val config = WalletSdkConfig.fromBuildConfig()
        WalletSigningPlugin.configure(config)
        WalletSigningPlugin.register(context.applicationContext)
    }

    /**
     * Main-only: FlutterEngineGroup + ProcessLifecycleOwner observer.
     * Must NOT swallow failures the way monolithic register() historically did for activator paths.
     */
    fun initFlutterEngine(context: Context) {
        FlutterEngineProvider.init(context.applicationContext)
    }

    @Volatile
    private var flutterEngineReadyOverrideForTests: Boolean? = null

    fun isFlutterEngineReady(): Boolean =
        flutterEngineReadyOverrideForTests ?: FlutterEngineProvider.isReady()

    /** Test-only override for [isFlutterEngineReady]; null restores real probe. */
    fun setFlutterEngineReadyForTests(ready: Boolean?) {
        flutterEngineReadyOverrideForTests = ready
    }

    override fun register(context: Context) {
        // Cold-start / ProofModeApp path (NF1): still a single Main-thread entry.
        registerWalletStack(context)
        try {
            initFlutterEngine(context)
        } catch (e: Throwable) {
            Timber.w(e, "LocationProtocolPlugin: Flutter engine init failed (non-fatal)")
        }
    }

    /** Bind the active wallet connector to [activity] (call from Activity.onStart). */
    fun bindWalletActivity(activity: Activity) {
        if (!isWalletStackRegistered()) return
        WalletSigningPlugin.providerSelection.activeConnector.bindActivity(activity)
    }

    /** Release the activity reference held by the wallet connector (call from Activity.onStop). */
    fun unbindWalletActivity() {
        if (!isWalletStackRegistered()) return
        WalletSigningPlugin.providerSelection.activeConnector.unbindActivity()
    }

    /** True when the wallet stack has a bound foreground Activity (on-chain submit prerequisite). */
    fun hasWalletActivityBound(): Boolean {
        if (!isWalletStackRegistered()) return false
        return when (val connector = WalletSigningPlugin.providerSelection.activeConnector) {
            is ZeroDevSmartAccountConnector -> connector.privyConnector.getActiveActivity() != null
            is PrivyWalletConnector -> connector.getActiveActivity() != null
            else -> false
        }
    }

    /** Whether [WalletSigningPlugin.register] has run (lpEnabled path). Router uses this before apply. */
    fun isWalletStackRegistered(): Boolean = WalletSigningPlugin.isRegistered()

    /** Pure parse/validate — no side effects. Wallet-infra-free return type. */
    fun parseWalletDeepLink(uri: Uri): WalletDeepLinkParseResult =
        WalletDeepLinkParser().parse(uri)

    /**
     * Parse, persist valid params to [WalletSessionStore], and sync chain on IO when applicable.
     * Caller must guard with [isWalletStackRegistered] before invoking (Phase 3 router).
     */
    suspend fun applyWalletDeepLink(context: Context, uri: Uri): WalletDeepLinkResult {
        require(isWalletStackRegistered()) {
            "Wallet stack not registered; check isWalletStackRegistered() before apply"
        }
        val sessionStore = requireNotNull(WalletSigningPlugin.sessionStore()) {
            "WalletSessionStore unavailable after registration"
        }
        val parseResult = WalletDeepLinkParser().parse(uri)
        return WalletDeepLinkApplier.apply(
            context = context,
            parseResult = parseResult,
            sessionStore = sessionStore,
            providerSelection = WalletSigningPlugin.providerSelection,
        )
    }

    /** Snapshot of current wallet state for share/attestation diagnostics. */
    fun walletDiagnostics(): WalletDiagnostics {
        if (!isWalletStackRegistered()) {
            return WalletDiagnostics(
                chainId = null,
                address = null,
                connectorName = "unregistered",
                sponsorshipActive = null,
                connected = false,
            )
        }
        val connector = WalletSigningPlugin.providerSelection.activeConnector
        val identity = connector.getIdentity()
        val sponsorshipActive = (connector as? WalletCapabilityProvider)?.isSponsorshipActive
        return WalletDiagnostics(
            chainId = identity?.chainId,
            address = identity?.address,
            connectorName = connector.javaClass.simpleName,
            sponsorshipActive = sponsorshipActive,
            connected = identity != null,
        )
    }

    /**
     * Suspend function — may fork a Flutter engine on first call.
     */
    suspend fun getBridge(context: Context): LPBridgeMessenger =
        FlutterEngineProvider.getBridge(context.applicationContext)

    /**
     * Creates a new [LocationProtocolAttestationCoordinator] backed by [storageProvider].
     *
     * Callers are responsible for scoping the coordinator to an appropriate lifecycle
     * (e.g. a ViewModel or Activity-scoped coroutine). This class does NOT implement
     * NotarizationProvider and is not registered as a plugin backend.
     */
    fun createCoordinator(
        storageProvider: StorageProvider,
        context: Context
    ): LocationProtocolAttestationCoordinator =
        LocationProtocolAttestationCoordinator(
            storageProvider = storageProvider,
            easManager = EASAttestationManager(
                bridgeProvider = {
                    getBridge(context.applicationContext)
                },
                walletSigner = requireNotNull(WalletSigningPlugin.providerSelection.activeSigner),
                transactionSender = requireNotNull(WalletSigningPlugin.providerSelection.activeTransactionSender),
                walletConnector = requireNotNull(WalletSigningPlugin.providerSelection.activeConnector)
            )
        )

    suspend fun restoreWalletSession(appContext: Context, scope: CoroutineScope) {
        WalletSigningPlugin.restoreBackgroundSession(appContext, scope)
    }
}
