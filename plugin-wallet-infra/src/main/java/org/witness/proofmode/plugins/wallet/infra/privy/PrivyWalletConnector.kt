package org.witness.proofmode.plugins.wallet.infra.privy

import android.app.Activity
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.privy.auth.AuthState
import io.privy.auth.PrivyUser
import io.privy.auth.LinkedAccount
import io.privy.logging.PrivyLogLevel
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig
import io.privy.wallet.ethereum.EthereumRpcRequest
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import org.witness.proofmode.plugins.wallet.infra.api.WalletAuthClient
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCancelledException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletChainMismatchException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletLifecycleException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNotInitializedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSigningException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletUnsupportedCapabilityException
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.internal.SendTransactionErrorMapper
import org.witness.proofmode.plugins.wallet.infra.model.WalletAuthenticating
import org.witness.proofmode.plugins.wallet.infra.model.WalletCapabilities
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnecting
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.model.WalletState

class PrivyWalletConnector(
    private val config: WalletSdkConfig,
    private val sessionStore: WalletSessionStore? = null,
) : WalletConnector, WalletSigner, WalletTransactionSender, WalletCapabilityProvider, WalletAuthClient {

    companion object {
        private const val TAG = "PrivyWalletConnector"
    }

    private var activityRef: WeakReference<Activity>? = null
    private var privy: Privy? = null
    private var identity: WalletIdentity? = null
    private var selectedChainId: String = config.defaultChainId
    private var authStateJob: Job? = null
    private val _creatingWallet = AtomicBoolean(false)

    private val _stateFlow = MutableStateFlow<WalletState>(WalletDisconnected)
    override val stateFlow: StateFlow<WalletState> = _stateFlow.asStateFlow()

    /** The current signer's Ethereum address, or empty string if not connected. */
    override val address: String
        get() = identity?.address ?: ""

    override fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    override fun unbindActivity() {
        activityRef?.clear()
        activityRef = null
    }

    fun getActiveActivity(): Activity? = activityRef?.get()

    override suspend fun connect() {
        val activity = requireActivity()
        _stateFlow.value = WalletConnecting("Checking Privy session")
        _stateFlow.value = WalletAuthenticating

        try {
            require(config.privyAppId.isNotBlank()) { "Missing PRIVY_APP_ID" }
            require(config.privyAppClientId.isNotBlank()) { "Missing PRIVY_APP_CLIENT_ID" }

            val client = ensurePrivy(activity, CoroutineScope(coroutineContext))
            client.getAuthState()

            val user = client.getUser()
                ?: throw WalletAuthException("No authenticated Privy user. Choose Email or SMS login first.")

            setConnectedIdentity(user)
        } catch (e: Throwable) {
            _stateFlow.value = WalletDisconnected
            throw mapAuthException(e)
        }
    }

    override suspend fun sendEmailCode(email: String) {
        val activity = requireActivity()
        _stateFlow.value = WalletConnecting("Sending email verification code")
        try {
            val client = ensurePrivy(activity, CoroutineScope(coroutineContext))
            client.getAuthState()
            client.email.sendCode(email).getOrElse { error ->
                throw WalletAuthException(error.message ?: "Failed to send email code", error)
            }
            _stateFlow.value = WalletDisconnected
        } catch (e: Throwable) {
            _stateFlow.value = WalletDisconnected
            throw mapAuthException(e)
        }
    }

    override suspend fun loginWithEmailCode(email: String, code: String) {
        val activity = requireActivity()
        _stateFlow.value = WalletAuthenticating
        try {
            val client = ensurePrivy(activity, CoroutineScope(coroutineContext))
            client.getAuthState()
            val user = client.email.loginWithCode(code, email).getOrElse { error ->
                throw WalletAuthException(error.message ?: "Email login failed", error)
            }
            setConnectedIdentity(user)
        } catch (e: Throwable) {
            _stateFlow.value = WalletDisconnected
            throw mapAuthException(e)
        }
    }

    override suspend fun sendSmsCode(phoneNumber: String) {
        val activity = requireActivity()
        _stateFlow.value = WalletConnecting("Sending SMS verification code")
        try {
            val client = ensurePrivy(activity, CoroutineScope(coroutineContext))
            client.getAuthState()
            client.sms.sendCode(phoneNumber).getOrElse { error ->
                throw WalletAuthException(error.message ?: "Failed to send SMS code", error)
            }
            _stateFlow.value = WalletDisconnected
        } catch (e: Throwable) {
            _stateFlow.value = WalletDisconnected
            throw mapAuthException(e)
        }
    }

    override suspend fun loginWithSmsCode(phoneNumber: String, code: String) {
        val activity = requireActivity()
        _stateFlow.value = WalletAuthenticating
        try {
            val client = ensurePrivy(activity, CoroutineScope(coroutineContext))
            client.getAuthState()
            val user = client.sms.loginWithCode(code, phoneNumber).getOrElse { error ->
                throw WalletAuthException(error.message ?: "SMS login failed", error)
            }
            setConnectedIdentity(user)
        } catch (e: Throwable) {
            _stateFlow.value = WalletDisconnected
            throw mapAuthException(e)
        }
    }

    override suspend fun disconnect() {
        try {
            authStateJob?.cancel()
            privy?.logout()
        } catch (_: Throwable) {
        } finally {
            authStateJob = null
            identity = null
            _stateFlow.value = WalletDisconnected
        }
    }

    override fun getIdentity(): WalletIdentity? = identity

    override fun setChain(chainId: String) {
        val previousChainId = selectedChainId
        selectedChainId = chainId
        sessionStore?.saveChainId(chainId)
        val connectedIdentity = identity
        if (connectedIdentity != null) {
            val updated = connectedIdentity.copy(chainId = chainId)
            identity = updated
            _stateFlow.value = WalletConnected(updated)
            Timber.tag(TAG).i(
                "setChain: %s → %s address=%s",
                previousChainId,
                chainId,
                abbreviateAddress(updated.address),
            )
        } else {
            Timber.tag(TAG).d("setChain: %s → %s (not connected)", previousChainId, chainId)
        }
    }

    override fun getCapabilities(): WalletCapabilities = WalletCapabilities(supportsSendTransaction = true)

    override val isSponsorshipActive: Boolean = false

    override suspend fun sendTransaction(params: Map<String, Any?>): Map<String, Any> {
        try {
            val requiredFields = listOf("to", "data", "valueHex", "chainId")
            for (key in requiredFields) {
                if (params[key] == null) {
                    throw WalletProviderSubmitException("Missing required field: $key")
                }
            }

            val requestChainId = params["chainId"] as String
            if (requestChainId != selectedChainId) {
                throw WalletChainMismatchException(expected = selectedChainId, actual = requestChainId)
            }

            val client = privy ?: throw WalletNotInitializedException()
            val user = client.getUser() ?: throw WalletAuthException("No authenticated user")

            val wallet = user.embeddedEthereumWallets.firstOrNull()
                ?: throw WalletUnsupportedCapabilityException("send-transaction")

            val address = identity?.address ?: getEmbeddedWalletAddress(user)
                ?: throw WalletProviderSubmitException("Embedded wallet address unavailable")

            val to = params["to"] as String
            val data = params["data"] as String
            val valueHex = params["valueHex"] as String

            Timber.tag(TAG).i(
                "sendTransaction: chainId=%s from=%s to=%s value=%s data=%s",
                requestChainId,
                abbreviateAddress(address),
                to,
                valueHex,
                truncateHex(data),
            )

            val txMap = mutableMapOf<String, Any>(
                "from" to address,
                "to" to to,
                "data" to data,
                "value" to valueHex,
                "chainId" to toRpcChainIdQuantity(requestChainId),
            )
            (params["nonceHex"] as? String)?.let { txMap["nonce"] = it }
            (params["gasLimitHex"] as? String)?.let {
                txMap["gas"] = it
                Timber.tag(TAG).d("sendTransaction: gasLimit=%s", it)
            }
            (params["gasPriceHex"] as? String)?.let { txMap["gasPrice"] = it }
            (params["maxFeePerGasHex"] as? String)?.let { txMap["maxFeePerGas"] = it }
            (params["maxPriorityFeePerGasHex"] as? String)?.let { txMap["maxPriorityFeePerGas"] = it }
            (params["type"] as? String)?.let { txMap["type"] = it }

            val txJson = org.json.JSONObject(txMap).toString()
            val request = EthereumRpcRequest(
                method = "eth_sendTransaction",
                params = listOf(txJson),
            )

            val rpcResponse = wallet.provider.request(request).getOrElse { error ->
                throw SendTransactionErrorMapper.fromProviderError(error.message, error)
            }

            val txHash = rpcResponse.data
            if (txHash.isBlank()) {
                throw WalletProviderSubmitException("eth_sendTransaction returned empty hash")
            }

            Timber.tag(TAG).i("sendTransaction: submitted txHash=%s chainId=%s", txHash, requestChainId)

            return mapOf(
                "txHash" to txHash,
                "chainId" to requestChainId,
                "submittedAtMs" to System.currentTimeMillis(),
            )
        } catch (e: WalletTransactionException) {
            throw e
        } catch (e: WalletException) {
            throw e
        } catch (e: Throwable) {
            throw WalletProviderSubmitException(e.message ?: "Unexpected error during sendTransaction", e)
        }
    }

    override suspend fun signTypedData(typedDataJson: String): String {
        try {
            val client = privy ?: throw WalletNotInitializedException("Privy not initialized")
            client.getAuthState()

            val user = client.getUser()
                ?: throw WalletAuthException("No authenticated Privy user")

            val wallet = user.embeddedEthereumWallets.firstOrNull()
                ?: throw WalletSigningException("No embedded Ethereum wallet available")

            val address = identity?.address ?: getEmbeddedWalletAddress(user)
            if (address.isNullOrBlank()) {
                throw WalletSigningException("Embedded wallet address unavailable")
            }

            // The Privy Kotlin SDK deserialises typed-data JSON into
            // EthereumTypedDataV4 which expects snake_case keys
            // (e.g. `primary_type`) rather than the EIP-712 standard
            // camelCase (`primaryType`).  Remap at the SDK boundary so
            // upstream code stays EIP-712 compliant.
            val privyTypedDataJson = toPrivyTypedDataFormat(typedDataJson)

            val request = EthereumRpcRequest(
                method = "eth_signTypedData_v4",
                params = listOf(address, privyTypedDataJson),
            )

            val rpcResponse = wallet.provider.request(request).getOrElse { error ->
                throw WalletSigningException(error.message ?: "Privy signing request failed", error)
            }

            val signature = rpcResponse.data
            if (signature.isBlank()) {
                throw WalletSigningException("Privy signing response was empty")
            }
            return signature
        } catch (e: Throwable) {
            if (e is WalletSigningException) throw e
            throw WalletSigningException(e.message ?: "Failed to sign typed data", e)
        }
    }

    /**
     * Initializes Privy using [applicationContext] without an Activity reference.
     * Uses [ProcessLifecycleOwner.get().lifecycleScope] for auth state observation.
     * Call from background workers or application-level initialization.
     */
    fun ensurePrivyBackground(appContext: Context, appScope: CoroutineScope) {
        val existing = privy
        if (existing != null) {
            startAuthStateObservation(appScope, existing)
            return
        }

        require(config.privyAppId.isNotBlank()) { "Missing PRIVY_APP_ID" }
        require(config.privyAppClientId.isNotBlank()) { "Missing PRIVY_APP_CLIENT_ID" }

        val created = Privy.init(
            context = appContext,
            config = PrivyConfig(
                appId = config.privyAppId,
                appClientId = config.privyAppClientId,
                logLevel = PrivyLogLevel.NONE,
            )
        )
        privy = created
        startAuthStateObservation(appScope, created)
    }

    internal fun requirePrivyInstance(): Privy {
        return privy ?: throw WalletNotInitializedException("Privy SDK not initialized")
    }

    private fun ensurePrivy(activity: Activity, fallbackScope: CoroutineScope?): Privy {
        val existing = privy
        if (existing != null) {
            startAuthStateObservation(resolveObservationScope(activity, fallbackScope), existing)
            return existing
        }

        val created = Privy.init(
            context = activity.applicationContext,
            config = PrivyConfig(
                appId = config.privyAppId,
                appClientId = config.privyAppClientId,
                logLevel = PrivyLogLevel.NONE,
            )
        )
        privy = created
        startAuthStateObservation(resolveObservationScope(activity, fallbackScope), created)
        return created
    }

    private fun resolveObservationScope(
        activity: Activity?,
        fallbackScope: CoroutineScope?,
    ): CoroutineScope {
        return ProcessLifecycleOwner.get().lifecycleScope
    }

    private fun getEmbeddedWalletAddress(user: io.privy.auth.PrivyUser): String? {
        val linkedAccountAddress = user.linkedAccounts
            .asSequence()
            .filterIsInstance<LinkedAccount.EmbeddedEthereumWalletAccount>()
            .map { it.address }
            .firstOrNull { it.isNotBlank() }

        return linkedAccountAddress
    }

    /**
     * Converts an app-level chain id (CAIP-2 `eip155:N` or decimal / hex quantity)
     * into the `0x`-prefixed hex quantity the Privy SDK expects for
     * [io.privy.wallet.walletApi.rpc.ethereum.UnsignedEthereumTransaction.chainId].
     */
    private fun toRpcChainIdQuantity(chainId: String): String {
        if (chainId.startsWith("0x", ignoreCase = true)) {
            return chainId
        }
        val numeric = chainId.removePrefix("eip155:")
        val value = numeric.toLongOrNull()
            ?: throw WalletProviderSubmitException("Invalid chainId: $chainId")
        return "0x${value.toString(16)}"
    }

    /**
     * Transforms standard EIP-712 typed-data JSON into the format expected
     * by the Privy Kotlin SDK.
     *
     * The Privy SDK's [io.privy.wallet.walletApi.rpc.ethereum.EthereumTypedDataV4]
     * uses `@SerialName("primary_type")` — i.e. **snake_case** — whereas the
     * EIP-712 standard uses camelCase (`primaryType`).  This function performs
     * the key rename at the JSON level so that upstream code can remain fully
     * EIP-712 compliant.
     */
    private fun toPrivyTypedDataFormat(eip712Json: String): String {
        try {
            val root = org.json.JSONObject(eip712Json)
            // Remap EIP-712 camelCase → Privy snake_case
            if (root.has("primaryType") && !root.has("primary_type")) {
                root.put("primary_type", root.remove("primaryType"))
            }
            return root.toString()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "toPrivyTypedDataFormat: could not remap keys, passing through")
            return eip712Json
        }
    }

    private fun startAuthStateObservation(scope: CoroutineScope?, client: Privy? = privy) {
        val resolvedScope = scope ?: ProcessLifecycleOwner.get().lifecycleScope

        val authStateFlow = client?.authState
        if (authStateFlow == null) {
            Timber.tag(TAG).w("Auth state observation not started: Privy authState unavailable")
            _stateFlow.value = WalletDisconnected
            return
        }

        authStateJob?.cancel()
        authStateJob = resolvedScope.launch {
            try {
                authStateFlow.collect { state ->
                    when (state) {
                        is AuthState.Authenticated -> _handleAuthenticated(state.user)
                        is AuthState.Unauthenticated -> _stateFlow.value = WalletDisconnected
                        else -> {}
                    }
                }
            } catch (_: Throwable) {
                _stateFlow.value = WalletDisconnected
            }
        }
    }

    private suspend fun _handleAuthenticated(user: PrivyUser) {
        val address = getEmbeddedWalletAddress(user)
        if (address.isNullOrBlank()) {
            _ensureEmbeddedWallet(user)
        } else {
            setConnectedIdentity(user)
        }
    }

    private suspend fun _ensureEmbeddedWallet(user: PrivyUser) {
        if (user.embeddedEthereumWallets.isNotEmpty()) {
            setConnectedIdentity(user)
            return
        }

        if (!_creatingWallet.compareAndSet(false, true)) {
            return
        }

        try {
            val result = user.createEthereumWallet(allowAdditional = false)
            result.onSuccess {
                setConnectedIdentity(user)
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Failed to auto-create embedded wallet")
                _stateFlow.value = WalletDisconnected
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Exception in _ensureEmbeddedWallet")
            _stateFlow.value = WalletDisconnected
        } finally {
            _creatingWallet.set(false)
        }
    }

    private fun setConnectedIdentity(user: PrivyUser) {
        val walletAddress = getEmbeddedWalletAddress(user)
            ?: throw WalletAuthException("No embedded Ethereum wallet found for user")
        val connectedIdentity = WalletIdentity(walletAddress, selectedChainId)
        identity = connectedIdentity
        sessionStore?.saveChainId(selectedChainId)
        _stateFlow.value = WalletConnected(connectedIdentity)
    }

    @VisibleForTesting
    internal fun getSelectedChainId(): String = selectedChainId

    private fun mapAuthException(throwable: Throwable): WalletException {
        val message = throwable.message.orEmpty().lowercase()
        if (
            message.contains("cancel") ||
            message.contains("dismiss") ||
            message.contains("reject")
        ) {
            return WalletCancelledException(throwable.message ?: "User cancelled")
        }

        return WalletAuthException(
            message = throwable.message ?: "Privy authentication failed",
            cause = throwable
        )
    }

    private fun requireActivity(): Activity {
        return activityRef?.get() ?: throw WalletNotInitializedException("Activity not bound")
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
