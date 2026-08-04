package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.witness.proofmode.plugins.lp.bridge.LPBridgeException
import org.witness.proofmode.plugins.lp.bridge.LPBridgeMessenger
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender
import org.witness.proofmode.plugins.wallet.infra.exception.WalletLifecycleException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSigningException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletTransactionException
import timber.log.Timber

/**
 * Builds EAS-compatible typed-data JSON structures for Location Protocol attestations.
 */
class EASAttestationManager private constructor(
    private val bridgeProvider: suspend () -> LPBridgeMessenger,
    private val walletSigner: WalletSigner,
    private val transactionSender: WalletTransactionSender,
    private val walletConnector: WalletConnector,
    private val jsonRpcClient: EthJsonRpcClient,
) {
    constructor(
        bridgeProvider: suspend () -> LPBridgeMessenger,
        walletSigner: WalletSigner,
        transactionSender: WalletTransactionSender,
        walletConnector: WalletConnector,
    ) : this(bridgeProvider, walletSigner, transactionSender, walletConnector, EthJsonRpcClient())

    companion object {
        internal fun forTesting(
            bridgeProvider: suspend () -> LPBridgeMessenger,
            walletSigner: WalletSigner,
            transactionSender: WalletTransactionSender,
            walletConnector: WalletConnector,
            jsonRpcClient: EthJsonRpcClient,
        ): EASAttestationManager =
            EASAttestationManager(
                bridgeProvider,
                walletSigner,
                transactionSender,
                walletConnector,
                jsonRpcClient,
            )

        /** Poll interval between receipt / mempool checks (Path B). */
        private const val POLL_INTERVAL_MS = 2000L

        /**
         * Fail fast when Privy returns a hash but the tx never reaches any Sepolia RPC mempool.
         */
        private const val BROADCAST_VERIFY_MAX_ATTEMPTS = 20

        /** Total receipt polls after submission (~4 minutes on Sepolia). */
        private const val RECEIPT_POLL_MAX_ATTEMPTS = 120

        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_PENDING_BROADCAST = "pending_broadcast"
    }

    suspend fun createOffchainLocationAttestation(
        payload: LocationProtocolPayload
    ): Result<LocationProtocolAttestationResult> {
        return try {
            val bridge = bridgeProvider.invoke()
            bridge.awaitReady()

            val attesterAddress = walletSigner.address

            val easTypedDataJson = bridge.invokeMethod(
                "build-eas-typed-data",
                mapOf("payload" to payload.toBridgePayload())
            ) as? String
                ?: return Result.failure(LPBridgeException("build-eas-typed-data returned non-string response"))

            val signature = walletSigner.signTypedData(easTypedDataJson)

            val bridgeResponse = bridge.invokeMethod(
                "create-offchain-attestation",
                mapOf(
                    "typedData" to easTypedDataJson,
                    "signature" to signature,
                    "attesterAddress" to attesterAddress,
                )
            )

            val mapped = bridgeResponse.toAttestationResult()
            Result.success(mapped)
        } catch (e: WalletSigningException) {
            Result.failure(e)
        } catch (e: LPBridgeException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitOnchainLocationAttestation(
        payload: LocationProtocolPayload,
    ): Result<OnchainSubmitResult> = withContext(Dispatchers.IO) {
        try {
            val bridge = bridgeProvider.invoke()
            bridge.awaitReady()

            val identity = walletConnector.getIdentity()
                ?: return@withContext Result.failure(Exception("Wallet identity missing"))
            val chainIdStr = identity.chainId
            val sponsorshipActive = walletConnector.sponsorshipActive()
            val onChainAttester = canonicalAttesterAddress()
            Timber.d(
                "EASAttestation: starting on-chain submit chainId=%s attesterAddress=%s sponsorshipActive=%s connector=%s",
                chainIdStr,
                abbreviateAddress(onChainAttester),
                sponsorshipActive,
                walletConnector.javaClass.simpleName,
            )

            val chainConfig = SUPPORTED_CHAINS.find { it.caip2Id == chainIdStr }
                ?: return@withContext Result.failure(Exception("Unsupported chain config"))
            if (chainConfig.rpcUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No RPC URL available"))
            }

            val bridgeResponse = bridge.invokeMethod(
                "build-eas-onchain-data",
                mapOf(
                    "payload" to payload.toBridgePayload(),
                    "chainId" to chainIdStr,
                )
            ) as? Map<*, *> ?: return@withContext Result.failure(
                Exception("build-eas-onchain-data returned invalid response")
            )

            val txData = bridgeResponse["txData"]?.toString()
                ?: return@withContext Result.failure(Exception("Missing txData"))
            val schemaId = bridgeResponse["schemaId"]?.toString()
                ?: return@withContext Result.failure(Exception("Missing schemaId"))
            val easAddress = bridgeResponse["easAddress"]?.toString()
                ?: return@withContext Result.failure(Exception("Missing easAddress"))
            val schemaRegistryAddress = bridgeResponse["schemaRegistryAddress"]?.toString()
                ?: return@withContext Result.failure(Exception("Missing schemaRegistryAddress"))

            if (onChainAttester.isBlank()) {
                return@withContext Result.failure(Exception("Wallet attester address unavailable"))
            }

            if (!jsonRpcClient.isSchemaRegistered(
                    chainConfig.rpcUrls,
                    schemaRegistryAddress,
                    schemaId,
                )
            ) {
                return@withContext Result.failure(
                    Exception(
                        "Location Protocol schema is not registered on ${chainConfig.displayName}. " +
                            "Switch your wallet to Sepolia testnet and try again."
                    )
                )
            }

            val txParams = mutableMapOf<String, Any>(
                "to" to easAddress,
                "data" to txData,
                "valueHex" to "0x0",
                "chainId" to chainIdStr,
            )
            val gasLimitHex = if (sponsorshipActive) {
                null
            } else {
                val estimated = jsonRpcClient.estimateGas(
                    rpcUrls = chainConfig.rpcUrls,
                    from = onChainAttester,
                    to = easAddress,
                    data = txData,
                    valueHex = "0x0",
                ) ?: return@withContext Result.failure(
                    WalletProviderSubmitException(
                        "Transaction simulation failed on ${chainConfig.displayName}. " +
                            "The proof may already be attested on-chain, or your wallet may lack gas. " +
                            "Confirm you are on Sepolia testnet."
                    )
                )
                addGasBuffer(estimated)
            }
            gasLimitHex?.let { txParams["gasLimitHex"] = it }

            val submittedAt = System.currentTimeMillis()
            Timber.i(
                "EASAttestation: sending transaction to=%s chainId=%s gasLimit=%s sponsorshipPath=%s",
                easAddress,
                chainIdStr,
                txParams["gasLimitHex"] ?: "sponsored-default",
                if (sponsorshipActive) "A-sponsored" else "B-self-funded",
            )
            val txResult = transactionSender.sendTransaction(txParams)
            val txHash = txResult["txHash"]?.toString()
                ?: return@withContext Result.failure(
                    WalletProviderSubmitException("Transaction failed without hash")
                )
            Timber.i("EASAttestation: transaction submitted txHash=%s", txHash)

            Result.success(
                OnchainSubmitResult(
                    txHash = txHash,
                    schemaId = schemaId,
                    easAddress = easAddress,
                    chainIdStr = chainIdStr,
                    rpcUrls = chainConfig.rpcUrls,
                    chainDisplayName = chainConfig.displayName,
                    submittedAt = submittedAt,
                    sponsorshipActive = sponsorshipActive,
                    onChainAttester = onChainAttester,
                )
            )
        } catch (e: WalletLifecycleException) {
            Result.failure(e)
        } catch (e: WalletSigningException) {
            Result.failure(e)
        } catch (e: WalletTransactionException) {
            Result.failure(e)
        } catch (e: LPBridgeException) {
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "EASAttestation: on-chain submit failed")
            Result.failure(e)
        }
    }

    suspend fun confirmOnchainLocationAttestation(
        submitResult: OnchainSubmitResult,
    ): Result<LocationProtocolAttestationResult> = withContext(Dispatchers.IO) {
        try {
            val receipt = pollForReceipt(
                rpcUrls = submitResult.rpcUrls,
                txHash = submitResult.txHash,
                sponsorshipActive = submitResult.sponsorshipActive,
                chainDisplayName = submitResult.chainDisplayName,
            )

            if (receipt == null) {
                Timber.w(
                    "EASAttestation: receipt timeout txHash=%s sponsorshipActive=%s",
                    submitResult.txHash,
                    submitResult.sponsorshipActive,
                )
                return@withContext Result.success(
                    buildPendingAttestationResult(submitResult)
                )
            }

            if (receipt.optString("status") != "0x1") {
                return@withContext Result.failure(Exception("Transaction reverted"))
            }

            val logs = receipt.optJSONArray("logs")
                ?: return@withContext Result.failure(Exception("Receipt missing logs"))
            val attestationUid = jsonRpcClient.parseAttestedUid(logs, submitResult.easAddress)
                ?: return@withContext Result.failure(Exception("Attested event not found in receipt logs"))

            val blockNumberHex = receipt.optString("blockNumber")
            val blockTimestamp = jsonRpcClient.getBlockTimestamp(submitResult.rpcUrls, blockNumberHex)
                ?: (System.currentTimeMillis() / 1000)
            val confirmedAt = System.currentTimeMillis()

            val attestationJsonObj = JSONObject()
            attestationJsonObj.put("status", STATUS_CONFIRMED)
            attestationJsonObj.put("txHash", submitResult.txHash)
            attestationJsonObj.put("attestationUid", attestationUid)
            attestationJsonObj.put("chainId", submitResult.chainIdStr)
            attestationJsonObj.put("contractAddress", submitResult.easAddress)
            attestationJsonObj.put("attesterAddress", submitResult.onChainAttester)
            attestationJsonObj.put("blockNumber", blockNumberHex)
            attestationJsonObj.put("blockTimestamp", blockTimestamp)
            attestationJsonObj.put("gasSponsored", submitResult.sponsorshipActive)
            attestationJsonObj.put("submittedAt", submitResult.submittedAt)
            attestationJsonObj.put("confirmedAt", confirmedAt)

            Result.success(
                LocationProtocolAttestationResult(
                    uid = attestationUid,
                    schemaId = submitResult.schemaId,
                    attesterAddress = submitResult.onChainAttester,
                    timestamp = blockTimestamp * 1000L,
                    offchainPayloadJson = attestationJsonObj.toString(),
                    artifactPath = "",
                )
            )
        } catch (e: WalletLifecycleException) {
            Result.failure(e)
        } catch (e: WalletSigningException) {
            Result.failure(e)
        } catch (e: WalletTransactionException) {
            Result.failure(e)
        } catch (e: LPBridgeException) {
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "EASAttestation: on-chain confirmation failed")
            Result.failure(e)
        }
    }

    suspend fun createOnchainLocationAttestation(
        payload: LocationProtocolPayload,
    ): Result<LocationProtocolAttestationResult> =
        submitOnchainLocationAttestation(payload).fold(
            onSuccess = { confirmOnchainLocationAttestation(it) },
            onFailure = { Result.failure(it) },
        )

    internal suspend fun pollForReceipt(
        rpcUrls: List<String>,
        txHash: String,
        sponsorshipActive: Boolean,
        chainDisplayName: String,
    ): JSONObject? {
        if (sponsorshipActive) {
            repeat(3) { attempt ->
                val receipt = jsonRpcClient.getTransactionReceipt(rpcUrls, txHash, attempt + 1)
                if (receipt != null) return receipt
                delay(500L)
            }
            return null
        }

        var txSeenOnChain = false
        for (attempt in 0 until RECEIPT_POLL_MAX_ATTEMPTS) {
            val pollAttempt = attempt + 1
            val transaction = jsonRpcClient.getTransactionByHash(rpcUrls, txHash)
            if (transaction != null) {
                txSeenOnChain = true
            } else if (pollAttempt >= BROADCAST_VERIFY_MAX_ATTEMPTS) {
                throw Exception(
                    "Wallet returned transaction hash $txHash but it never appeared on " +
                        "$chainDisplayName. The transaction may be stuck in Privy's " +
                        "broadcast queue — check your Privy dashboard or block explorer, " +
                        "then try again in a few minutes."
                )
            }

            val receipt = jsonRpcClient.getTransactionReceipt(
                rpcUrls = rpcUrls,
                txHash = txHash,
                pollAttempt = pollAttempt,
            )
            if (receipt != null) {
                return receipt
            }
            delay(POLL_INTERVAL_MS)
        }

        if (txSeenOnChain) {
            return null
        }
        throw Exception(
            "Transaction receipt timeout for $txHash on $chainDisplayName. " +
                "Check block explorer for confirmation status."
        )
    }

    internal fun buildPendingAttestationResult(
        submitResult: OnchainSubmitResult,
    ): LocationProtocolAttestationResult {
        val attestationJsonObj = JSONObject()
        attestationJsonObj.put("status", STATUS_PENDING_BROADCAST)
        attestationJsonObj.put("txHash", submitResult.txHash)
        attestationJsonObj.put("chainId", submitResult.chainIdStr)
        attestationJsonObj.put("contractAddress", submitResult.easAddress)
        attestationJsonObj.put("attesterAddress", submitResult.onChainAttester)
        attestationJsonObj.put("gasSponsored", submitResult.sponsorshipActive)
        attestationJsonObj.put("submittedAt", submitResult.submittedAt)

        return LocationProtocolAttestationResult(
            uid = "",
            schemaId = submitResult.schemaId,
            attesterAddress = submitResult.onChainAttester,
            timestamp = submitResult.submittedAt,
            offchainPayloadJson = attestationJsonObj.toString(),
            artifactPath = "",
        )
    }

    private fun canonicalAttesterAddress(): String = walletSigner.address

    private fun abbreviateAddress(address: String): String {
        if (address.length <= 12) return address
        return "${address.take(6)}…${address.takeLast(4)}"
    }

    private fun addGasBuffer(estimatedGasHex: String): String {
        val estimate = estimatedGasHex.removePrefix("0x").toLong(16)
        val buffered = estimate * 120 / 100
        return "0x${buffered.toString(16)}"
    }

    private fun LocationProtocolPayload.toBridgePayload(): Map<String, Any?> = mapOf(
        "eventTimestamp" to eventTimestamp,
        "srs" to srs,
        "locationType" to locationType,
        "location" to location,
        "recipeType" to recipeType.toList(),
        "recipePayload" to recipePayload.toList(),
        "mediaType" to mediaType.toList(),
        "mediaData" to mediaData.toList(),
        "memo" to memo,
    )

    private fun Any?.toAttestationResult(): LocationProtocolAttestationResult {
        val map = this as? Map<*, *> ?: throw IllegalStateException("Bridge response is not a map")

        return LocationProtocolAttestationResult(
            uid = map["uid"]?.toString().orEmpty(),
            schemaId = map["schemaId"]?.toString()
                ?: map["schemaUID"]?.toString().orEmpty(),
            attesterAddress = map["attesterAddress"]?.toString().orEmpty(),
            timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            offchainPayloadJson = map["offchainPayloadJson"]?.toString().orEmpty(),
            artifactPath = map["artifactPath"]?.toString().orEmpty(),
        )
    }
}

private fun WalletConnector.sponsorshipActive(): Boolean =
    (this as? WalletCapabilityProvider)?.isSponsorshipActive == true
