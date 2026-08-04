package org.witness.proofmode.plugins.wallet.infra.zerodev

import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.zerodev.aa.SignerImpl
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EthereumRpcRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
import org.witness.proofmode.plugins.wallet.infra.exception.WalletLifecycleException
import timber.log.Timber

/**
 * A signing bridge that implements ZeroDev's [SignerImpl] interface by delegating
 * cryptographic operations to Privy's embedded wallet.
 *
 * ZeroDev calls [signHash] for final UserOp ECDSA signing and [signMessage] for
 * paymaster-stub / gas-estimation signing. Both must be implemented for sponsored
 * UserOps to succeed.
 *
 * All operations must run off the main thread since Privy's RPC calls are asynchronous
 * and the bridge must block the calling thread to return synchronously to the SDK.
 */
class PrivyZeroDevBridge(
    private val privyConnector: PrivyWalletConnector,
    private val scope: kotlinx.coroutines.CoroutineScope = ProcessLifecycleOwner.get().lifecycleScope
) : SignerImpl {

    companion object {
        private const val TAG = "PrivyZeroDevBridge"
    }

    override fun getAddress(): ByteArray {
        val addr = privyConnector.address
        if (addr.isBlank()) {
            throw WalletLifecycleException("wallet not connected or address missing")
        }
        return addr.hexToByteArray()
    }

    override fun signHash(hash: ByteArray): ByteArray {
        assertBackgroundThread("signHash")
        return signWithPrivy(
            operation = "signHash",
            payload = hash,
            buildRequest = { _, hexPayload ->
                EthereumRpcRequest.secp256k1Sign(hexPayload)
            },
        )
    }

    override fun signMessage(msg: ByteArray): ByteArray {
        assertBackgroundThread("signMessage")
        return signWithPrivy(
            operation = "signMessage",
            payload = msg,
            buildRequest = { wallet, hexPayload ->
                EthereumRpcRequest.personalSign(hexPayload, wallet.address)
            },
        )
    }

    override fun signTypedDataHash(hash: ByteArray): ByteArray = signHash(hash)

    private fun assertBackgroundThread(operation: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw WalletLifecycleException("$operation on main thread will deadlock")
        }
    }

    private fun signWithPrivy(
        operation: String,
        payload: ByteArray,
        buildRequest: (EmbeddedEthereumWallet, String) -> EthereumRpcRequest,
    ): ByteArray {
        val hexPayload = "0x" + payload.toHexString()
        Timber.tag(TAG).d(
            "%s: payloadBytes=%d payloadPrefix=%s",
            operation,
            payload.size,
            abbreviateHex(hexPayload),
        )

        val deferred = CompletableDeferred<ByteArray>()
        scope.launch {
            try {
                val client = privyConnector.requirePrivyInstance()
                val user = client.getUser()
                    ?: throw WalletLifecycleException("No authenticated Privy user")
                val wallet = user.embeddedEthereumWallets.firstOrNull()
                    ?: throw WalletLifecycleException("No embedded Ethereum wallet available")

                val request = buildRequest(wallet, hexPayload)
                Timber.tag(TAG).d("%s: rpcMethod=%s wallet=%s", operation, request.method, abbreviateHex(wallet.address))

                val rpcResponse = wallet.provider.request(request).getOrThrow()
                val signatureHex = rpcResponse.data
                if (signatureHex.isBlank()) {
                    throw RuntimeException("empty signature from Privy")
                }

                Timber.tag(TAG).d("%s: success signaturePrefix=%s", operation, abbreviateHex(signatureHex))
                deferred.complete(signatureHex.hexToByteArray())
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "%s: failed rpcMethod may be unsupported or wallet unavailable", operation)
                deferred.completeExceptionally(e)
            }
        }

        return runBlocking {
            deferred.await()
        }
    }

    private fun abbreviateHex(value: String?, maxChars: Int = 14): String {
        if (value.isNullOrBlank()) return "(empty)"
        if (value.length <= maxChars) return value
        return "${value.take(maxChars)}…"
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun String.hexToByteArray(): ByteArray {
        val clean = this.removePrefix("0x")
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
