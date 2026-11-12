package org.witness.proofmode.plugins.wallet.infra.zerodev

import android.app.Activity
import androidx.annotation.VisibleForTesting
import dev.zerodev.aa.Account
import dev.zerodev.aa.Context
import dev.zerodev.aa.Signer
import dev.zerodev.aa.Address
import dev.zerodev.aa.Call
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.plugins.wallet.infra.abbreviateAddress
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletLifecycleException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNotInitializedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import org.witness.proofmode.plugins.wallet.infra.hexToByteArray
import org.witness.proofmode.plugins.wallet.infra.internal.SendTransactionErrorMapper
import org.witness.proofmode.plugins.wallet.infra.model.WalletAuthenticating
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnecting
import org.witness.proofmode.plugins.wallet.infra.model.WalletCreatingWallet
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
import org.witness.proofmode.plugins.wallet.infra.truncateHex
import timber.log.Timber
import java.math.BigInteger

/**
 * Wraps ZeroDev smart-account execution behind [WalletConnector], [WalletSigner], and
 * [WalletTransactionSender]. Under EIP-7702 (spec §4.2), [address] is always the Privy EOA —
 * the sole public identity for signing and on-chain `msg.sender`.
 */
class ZeroDevSmartAccountConnector(
    val privyConnector: PrivyWalletConnector,
    private val configResolver: (chainId: String) -> ZeroDevConfig,
) : WalletConnector, WalletSigner, WalletTransactionSender, WalletCapabilityProvider {

    companion object {
        private const val TAG = "ZeroDevConnector"
    }

    /**
     * What the live account (sponsored or self-funded EOA) was last built from. A setChain whose
     * target matches it would rebuild the same thing, so it is skipped. Holding the resolved
     * config, not only the chain, keeps a toggle flip, project override, or URL change re-initing.
     */
    private data class AppliedKey(val chainId: String, val config: ZeroDevConfig)

    @Volatile
    private var appliedKey: AppliedKey? = null

    private var account: Account? = null
    private var zeroDevContext: Context? = null
    private var zeroDevSigner: Signer? = null
    private val _stateFlow = MutableStateFlow<WalletState>(WalletDisconnected)
    override val stateFlow: StateFlow<WalletState> = _stateFlow.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @VisibleForTesting
    internal var sponsoredAccountProvisioner: SponsoredAccountProvisioner = DefaultSponsoredAccountProvisioner

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    init {
        privyConnector.stateFlow.onEach { privyState ->
            when (_stateFlow.value) {
                is WalletDisconnected, is WalletAuthenticating,
                is WalletCreatingWallet, is WalletUnavailable, is WalletAwaitingNetwork -> {
                    if (privyState is WalletCreatingWallet ||
                        privyState is WalletUnavailable ||
                        ((privyState is WalletDisconnected || privyState is WalletAwaitingNetwork) &&
                            _stateFlow.value !is WalletAuthenticating)
                    ) {
                        _stateFlow.value = privyState
                    } else if (privyState is WalletConnected) {
                        val chainId = privyState.identity.chainId
                        val config = configResolver(chainId)
                        if (effectiveSponsorshipAllowed(config)) {
                            scope.launch {
                                val restored = withContext(ioDispatcher) {
                                    initializeSponsoredAccount(chainId, config)
                                }
                                _stateFlow.value = restored ?: WalletConnected(
                                    WalletIdentity(privyConnector.address, chainId),
                                )
                                Timber.tag(TAG).i(
                                    "coldStartRestore: chainId=%s effectiveAllowed=%s sponsorshipActive=%s",
                                    chainId,
                                    effectiveSponsorshipAllowed(config),
                                    isSponsorshipActive,
                                )
                            }
                        } else {
                            applySelfFunded(chainId, config)
                        }
                    }
                }
                is WalletConnecting -> { /* wrapper owns connect/setChain spinner */ }
                is WalletConnected -> { /* wrapper may re-emit after setChain */ }
            }
        }.launchIn(scope)
    }

    override var isSponsorshipActive: Boolean = false
        internal set

    override val address: String
        get() = privyConnector.address

    override fun bindActivity(activity: Activity) {
        privyConnector.bindActivity(activity)
    }

    override fun unbindActivity() {
        privyConnector.unbindActivity()
    }

    override suspend fun retryWallet() {
        privyConnector.retryWallet()
    }

    override suspend fun refreshSession() {
        privyConnector.refreshSession()
    }

    override fun getIdentity(): WalletIdentity? = privyConnector.getIdentity()

    /**
     * Initiates connection. It first ensures Privy connection. If Privy is connected, it
     * resolves ZeroDevConfig for the selected chainId. If sponsorship is disabled or URLs
     * are blank (zero-config invariant guard), it defaults to self-funded EOA path.
     * Otherwise, it initializes the ZeroDev sponsored smart account.
     */
    override suspend fun connect() {
        _stateFlow.value = WalletConnecting("Initializing Wallet Connection")
        if (privyConnector.getIdentity() == null) {
            privyConnector.connect()
        }
        val identity = privyConnector.getIdentity()
            ?: throw WalletLifecycleException("chainId unavailable")
        val chainId = identity.chainId
        val config = configResolver(chainId)

        if (!effectiveSponsorshipAllowed(config)) {
            applySelfFunded(chainId, config)
            return
        }

        Timber.tag(TAG).i("connect: sponsored smart account chainId=%s", chainId)
        val connected = initializeSponsoredAccount(chainId, config)
        if (connected == null) {
            disconnect()
            throw WalletAuthException("ZeroDev initialization failed")
        }
        _stateFlow.value = connected
        Timber.tag(TAG).d(
            "connect: sponsored smart account initialized smartAccount=%s sponsorshipActive=true",
            abbreviateAddress(connected.identity.address),
        )
    }

    /**
     * Disconnects the wallet, cleaning up both the delegate Privy connector
     * and any instantiated ZeroDev SDK resources.
     */
    override suspend fun disconnect() {
        try {
            privyConnector.disconnect()
        } finally {
            cleanUpZeroDev()
            isSponsorshipActive = false
            _stateFlow.value = WalletDisconnected
        }
    }

    /**
     * Switches the active network. This triggers a full teardown of the existing
     * ZeroDev account context (if any), re-evaluates the zero-config invariant guard,
     * and either re-initializes ZeroDev on the new chain or falls back to Privy EOA.
     */
    override fun setChain(chainId: String) {
        val previousChainId = privyConnector.getIdentity()?.chainId
        Timber.tag(TAG).i("setChain: %s → %s", previousChainId, chainId)
        privyConnector.setChain(chainId)

        if (privyConnector.getIdentity() == null) {
            Timber.tag(TAG).d(
                "setChain: chain preference persisted, not connected — skipping re-init",
            )
            return
        }

        val config = configResolver(chainId)
        if (_stateFlow.value is WalletConnected &&
            appliedKey == AppliedKey(chainId, config) &&
            isSponsorshipActive == effectiveSponsorshipAllowed(config)
        ) {
            Timber.tag(TAG).d("setChain: chainId=%s already applied with the same config — skipping re-init", chainId)
            return
        }

        _stateFlow.value = WalletConnecting("Switching chain to $chainId")
        cleanUpZeroDev()

        if (!effectiveSponsorshipAllowed(config)) {
            applySelfFunded(chainId, config)
            return
        }

        Timber.tag(TAG).i("setChain: sponsored smart account chainId=%s", chainId)
        val connected = initializeSponsoredAccount(chainId, config)
        if (connected == null) {
            _stateFlow.value = WalletDisconnected
            return
        }
        _stateFlow.value = connected
        Timber.tag(TAG).d(
            "setChain: sponsored smart account initialized smartAccount=%s sponsorshipActive=true",
            abbreviateAddress(connected.identity.address),
        )
    }

    /** Self-funded EOA: no ZeroDev account; the Privy address stays the identity. */
    private fun applySelfFunded(chainId: String, config: ZeroDevConfig) {
        isSponsorshipActive = false
        Timber.tag(TAG).i(
            "self-funded EOA chainId=%s sponsorshipEnabled=%s projectPresent=%s",
            chainId,
            config.isSponsorshipEnabled,
            config.projectId.isNotBlank(),
        )
        appliedKey = AppliedKey(chainId, config)
        _stateFlow.value = WalletConnected(WalletIdentity(privyConnector.address, chainId))
    }

    private fun effectiveSponsorshipAllowed(config: ZeroDevConfig): Boolean =
        config.isSponsorshipEnabled
            && config.projectId.isNotBlank()

    /** Provisions the sponsored account; on any failure cleans up and returns null. */
    private fun initializeSponsoredAccount(chainId: String, config: ZeroDevConfig): WalletConnected? =
        try {
            val session = sponsoredAccountProvisioner.provision(chainId, config, privyConnector)
            zeroDevSigner = session.signer
            zeroDevContext = session.context
            account = session.account
            isSponsorshipActive = true
            appliedKey = AppliedKey(chainId, config)
            WalletConnected(WalletIdentity(session.account.getAddress().toString(), chainId))
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "sponsored smart account initialization failed chainId=%s", chainId)
            cleanUpZeroDev()
            isSponsorshipActive = false
            null
        }

    private fun cleanUpZeroDev() {
        try {
            account?.close()
        } catch (_: Throwable) {}
        try {
            zeroDevContext?.close()
        } catch (_: Throwable) {}
        try {
            zeroDevSigner?.close()
        } catch (_: Throwable) {}
        account = null
        zeroDevContext = null
        zeroDevSigner = null
        appliedKey = null
    }

    /**
     * Sends a transaction. If sponsorship is active, it parses transaction parameters,
     * builds a ZeroDev Call list, submits it via the bundler/paymaster, awaits receipt, and returns
     * the transaction hash. Otherwise, it delegates directly to PrivyWalletConnector (self-funded EOA).
     */
    override suspend fun sendTransaction(params: Map<String, Any?>): Map<String, Any> {
        require(privyConnector.getActiveActivity() != null) {
            throw WalletLifecycleException("Missing foreground Activity for Privy authentication")
        }

        try {
            return withContext(Dispatchers.IO) {
                val to = params["to"] as? String ?: throw WalletProviderSubmitException("Missing 'to'")
                val data = params["data"] as? String ?: throw WalletProviderSubmitException("Missing 'data'")
                val valueHex = params["valueHex"] as? String ?: throw WalletProviderSubmitException("Missing 'valueHex'")
                val chainId = params["chainId"] as? String ?: throw WalletProviderSubmitException("Missing 'chainId'")

                if (isSponsorshipActive) {
                    Timber.tag(TAG).i(
                        "sendTransaction: sponsored smart account chainId=%s to=%s value=%s data=%s",
                        chainId,
                        to,
                        valueHex,
                        truncateHex(data),
                    )
                    val activeAccount = account ?: throw WalletNotInitializedException("ZeroDev account not initialized")

                    val valueBigInt = BigInteger(valueHex.removePrefix("0x"), 16)
                    val targetAddress = Address.fromHex(to)
                    val calldataBytes = data.hexToByteArray()

                    val call = Call(targetAddress, valueBigInt.toByteArray(), calldataBytes)
                    val userOpHash = activeAccount.sendUserOp(listOf(call))
                    val receipt = activeAccount.waitForUserOperationReceipt(userOpHash)

                    val jsonString = receipt.receipt.toString()
                    val jsonObject = org.json.JSONObject(jsonString)
                    val txHash = jsonObject.optString("transactionHash")
                    if (txHash.isNullOrBlank()) {
                        throw WalletProviderSubmitException("Transaction hash missing from bundler receipt: $jsonString")
                    }

                    Timber.tag(TAG).i("sendTransaction: sponsored smart account submitted txHash=%s", txHash)
                    mapOf(
                        "txHash" to txHash,
                        "chainId" to chainId,
                        "submittedAtMs" to System.currentTimeMillis(),
                    )
                } else {
                    Timber.tag(TAG).i(
                        "sendTransaction: self-funded EOA delegating to Privy chainId=%s to=%s value=%s data=%s",
                        chainId,
                        to,
                        valueHex,
                        truncateHex(data),
                    )
                    privyConnector.sendTransaction(params)
                }
            }
        } catch (e: WalletException) {
            throw e
        } catch (e: Throwable) {
            throw SendTransactionErrorMapper.fromProviderError(
                e.message ?: "Unexpected error during sendTransaction",
                e,
            )
        }
    }

    /**
     * Delegates typed data signing entirely to PrivyWalletConnector to preserve the EOA-level
     * unified cryptographic identity invariant for offchain signatures (such as EAS attestations).
     */
    override suspend fun signTypedData(typedDataJson: String): String {
        return privyConnector.signTypedData(typedDataJson)
    }
}
