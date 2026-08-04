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
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletLifecycleException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNotInitializedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionException
import org.witness.proofmode.plugins.wallet.infra.internal.SendTransactionErrorMapper
import org.witness.proofmode.plugins.wallet.infra.model.WalletAuthenticating
import org.witness.proofmode.plugins.wallet.infra.model.WalletCapabilities
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnecting
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
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

    private enum class SponsoredInitPolicy {
        /** connect(): cleanUpZeroDev(), throw WalletAuthException — caller disconnects */
        FatalOnConnect,
        /** setChain() SDK failure: cleanUpZeroDev(), WalletDisconnected — unchanged today */
        DisconnectOnChainSwitch,
        /** cold-start restore SDK failure: cleanUpZeroDev(), return null → caller emits self-funded EOA */
        SelfFundedFallbackOnRestore,
    }

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
                is WalletDisconnected, is WalletAuthenticating -> {
                    if (privyState is WalletConnected) {
                        val chainId = privyState.identity.chainId
                        if (isSponsorshipActive && account != null &&
                            privyConnector.getIdentity()?.chainId == chainId
                        ) {
                            Timber.tag(TAG).d(
                                "coldStartRestore: skip re-init chainId=%s sponsorshipAlreadyActive=true",
                                chainId,
                            )
                            return@onEach
                        }
                        val config = configResolver(chainId)
                        if (effectiveSponsorshipAllowed(config)) {
                            scope.launch {
                                val restored = withContext(ioDispatcher) {
                                    initializeSponsoredAccount(
                                        chainId,
                                        SponsoredInitPolicy.SelfFundedFallbackOnRestore,
                                    )
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
                            isSponsorshipActive = false
                            _stateFlow.value = WalletConnected(
                                WalletIdentity(privyConnector.address, chainId),
                            )
                            Timber.tag(TAG).i(
                                "coldStartRestore: chainId=%s effectiveAllowed=false sponsorshipActive=false",
                                chainId,
                            )
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

    /**
     * Whether sponsored smart-account execution is active for the current session.
     *
     * Addendum §C deactivation rows:
     * - `sponsor_transactions_enabled = false` → self-funded EOA via `config.isSponsorshipEnabled = false`
     * - Sponsored init not completed this session → remains `false` until `connect()`, `setChain()`,
     *   or cold-start restore succeeds
     */

    fun getBundlerUrl(chainId: String): String {
        return configResolver(chainId).bundlerUrl
    }

    override val address: String
        get() = privyConnector.address

    override fun bindActivity(activity: Activity) {
        privyConnector.bindActivity(activity)
    }

    override fun unbindActivity() {
        privyConnector.unbindActivity()
    }

    override fun getCapabilities(): WalletCapabilities {
        return WalletCapabilities(supportsSendTransaction = true)
    }

    override fun getIdentity(): WalletIdentity? {
        val privyIdentity = privyConnector.getIdentity()
            ?: return null
        val addr = privyConnector.address
        if (addr.isBlank()) return null
        return WalletIdentity(privyConnector.address, privyIdentity.chainId)
    }

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
            isSponsorshipActive = false
            Timber.tag(TAG).i(
                "connect: self-funded EOA chainId=%s sponsorshipEnabled=%s projectPresent=%s bundlerPresent=%s paymasterPresent=%s",
                chainId,
                config.isSponsorshipEnabled,
                config.projectId.isNotBlank(),
                config.bundlerUrl.isNotBlank(),
                config.paymasterUrl.isNotBlank(),
            )
            _stateFlow.value = WalletConnected(WalletIdentity(privyConnector.address, chainId))
            return
        }

        try {
            Timber.tag(TAG).i(
                "connect: sponsored smart account chainId=%s bundlerPresent=true paymasterPresent=true",
                chainId,
            )
            val connected = initializeSponsoredAccount(chainId, SponsoredInitPolicy.FatalOnConnect)
            if (connected != null) {
                _stateFlow.value = connected
                Timber.tag(TAG).d(
                    "connect: sponsored smart account initialized smartAccount=%s sponsorshipActive=true",
                    abbreviateAddress(connected.identity.address),
                )
            }
        } catch (e: WalletAuthException) {
            isSponsorshipActive = false
            Timber.tag(TAG).e(e, "connect: sponsored smart account initialization failed, disconnecting")
            disconnect()
            _stateFlow.value = WalletDisconnected
            throw e
        }
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

        _stateFlow.value = WalletConnecting("Switching chain to $chainId")
        cleanUpZeroDev()

        val config = configResolver(chainId)

        if (!effectiveSponsorshipAllowed(config)) {
            isSponsorshipActive = false
            Timber.tag(TAG).i(
                "setChain: self-funded EOA chainId=%s sponsorshipEnabled=%s projectPresent=%s bundlerPresent=%s paymasterPresent=%s",
                chainId,
                config.isSponsorshipEnabled,
                config.projectId.isNotBlank(),
                config.bundlerUrl.isNotBlank(),
                config.paymasterUrl.isNotBlank(),
            )
            _stateFlow.value = WalletConnected(WalletIdentity(privyConnector.address, chainId))
            return
        }

        Timber.tag(TAG).i(
            "setChain: sponsored smart account chainId=%s bundlerPresent=true paymasterPresent=true",
            chainId,
        )
        val connected = initializeSponsoredAccount(chainId, SponsoredInitPolicy.DisconnectOnChainSwitch)
        if (connected != null) {
            _stateFlow.value = connected
            Timber.tag(TAG).d(
                "setChain: sponsored smart account initialized smartAccount=%s sponsorshipActive=true",
                abbreviateAddress(connected.identity.address),
            )
        }
    }

    private fun effectiveSponsorshipAllowed(config: ZeroDevConfig): Boolean =
        config.isSponsorshipEnabled
            && config.projectId.isNotBlank()
            && config.bundlerUrl.isNotBlank()
            && config.paymasterUrl.isNotBlank()

    private fun initializeSponsoredAccount(
        chainId: String,
        policy: SponsoredInitPolicy,
    ): WalletConnected? {
        val config = configResolver(chainId)
        return try {
            val session = sponsoredAccountProvisioner.provision(chainId, config, privyConnector)
            zeroDevSigner = session.signer
            zeroDevContext = session.context
            account = session.account
            isSponsorshipActive = true
            WalletConnected(WalletIdentity(session.account.getAddress().toString(), chainId))
        } catch (e: Throwable) {
            when (policy) {
                SponsoredInitPolicy.FatalOnConnect -> {
                    cleanUpZeroDev()
                    isSponsorshipActive = false
                    throw WalletAuthException("ZeroDev initialization failed", e)
                }
                SponsoredInitPolicy.DisconnectOnChainSwitch -> {
                    isSponsorshipActive = false
                    Timber.tag(TAG).e(e, "setChain: sponsored smart account initialization failed")
                    cleanUpZeroDev()
                    _stateFlow.value = WalletDisconnected
                    null
                }
                SponsoredInitPolicy.SelfFundedFallbackOnRestore -> {
                    cleanUpZeroDev()
                    isSponsorshipActive = false
                    null
                }
            }
        }
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
        } catch (e: WalletTransactionException) {
            throw e
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

    private fun String.hexToByteArray(): ByteArray {
        val clean = this.removePrefix("0x")
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun abbreviateAddress(address: String): String {
        if (address.length <= 12) return address
        return "${address.take(6)}…${address.takeLast(4)}"
    }

    private fun truncateHex(value: String, maxChars: Int = 18): String {
        if (value.length <= maxChars) return value
        return "${value.take(maxChars)}…(${value.length} chars)"
    }
}
