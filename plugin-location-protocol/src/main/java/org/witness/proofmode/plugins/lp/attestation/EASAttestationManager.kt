package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.witness.proofmode.plugins.lp.bridge.LPBridgeException
import org.witness.proofmode.plugins.lp.bridge.LPBridgeMessenger
import org.witness.proofmode.plugins.lp.config.ChainConfig
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.wallet.infra.abbreviateAddress
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender
import org.witness.proofmode.plugins.wallet.infra.exception.WalletProviderSubmitException
import timber.log.Timber

/**
 * Builds EAS-compatible typed-data JSON structures for Location Protocol attestations.
 */
class EASAttestationManager internal constructor(
    private val bridgeProvider: suspend () -> LPBridgeMessenger,
    private val walletSigner: WalletSigner,
    private val transactionSender: WalletTransactionSender,
    private val walletConnector: WalletConnector,
    private val jsonRpcClient: EthJsonRpcClient,
) {
    private val easVersionResolver = EasVersionResolver(jsonRpcClient)

    constructor(
        bridgeProvider: suspend () -> LPBridgeMessenger,
        walletSigner: WalletSigner,
        transactionSender: WalletTransactionSender,
        walletConnector: WalletConnector,
    ) : this(
        bridgeProvider,
        walletSigner,
        transactionSender,
        walletConnector,
        EthJsonRpcClient(),
    )

    companion object {
        /** Poll interval between receipt checks (Path B). */
        private const val POLL_INTERVAL_MS = 2000L

        /** Total receipt polls after submission (~4 minutes at [POLL_INTERVAL_MS]). */
        private const val RECEIPT_POLL_MAX_ATTEMPTS = 120

        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_PENDING_BROADCAST = "pending_broadcast"
    }

    suspend fun createOffchainLocationAttestation(
        payload: LocationProtocolPayload
    ): Result<LocationProtocolAttestationResult> {
        return try {
            val chainConfig = resolveSelectedChain()
            val bridge = bridgeProvider.invoke()
            bridge.awaitReady()

            val attesterAddress = walletSigner.address

            val easVersion = resolveEasVersion(bridge, chainConfig)

            val easTypedDataJson = bridge.invokeMethod(
                "build-eas-typed-data",
                mapOf(
                    "payload" to payload.toBridgePayload(),
                    "chainId" to chainConfig.caip2Id,
                    "easVersion" to easVersion,
                )
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

            val chainConfig = resolveSelectedChain()
            val chainIdStr = chainConfig.caip2Id
            val sponsorshipActive = walletConnector.sponsorshipActive()
            val onChainAttester = canonicalAttesterAddress()
            Timber.d(
                "EASAttestation: starting on-chain submit chainId=%s attesterAddress=%s sponsorshipActive=%s connector=%s",
                chainIdStr,
                abbreviateAddress(onChainAttester),
                sponsorshipActive,
                walletConnector.javaClass.simpleName,
            )

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
            val easAddress = bridgeResponse["easAddress"]?.toString()
                ?: return@withContext Result.failure(Exception("Missing easAddress"))

            if (onChainAttester.isBlank()) {
                return@withContext Result.failure(Exception("Wallet attester address unavailable"))
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
                        "Transaction simulation failed on ${chainConfig.displayName}"
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
                    easAddress = easAddress,
                    chainIdStr = chainIdStr,
                    rpcUrls = chainConfig.rpcUrls,
                    submittedAt = submittedAt,
                    sponsorshipActive = sponsorshipActive,
                    onChainAttester = onChainAttester,
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "EASAttestation: on-chain submit failed")
            Result.failure(e)
        }
    }

    internal suspend fun confirmOnchainLocationAttestation(
        submitResult: OnchainSubmitResult,
    ): OnchainConfirmationOutcome = withContext(Dispatchers.IO) {
        try {
            val receipt = pollForReceipt(
                rpcUrls = submitResult.rpcUrls,
                txHash = submitResult.txHash,
                sponsorshipActive = submitResult.sponsorshipActive,
            )

            if (receipt == null) {
                Timber.w(
                    "EASAttestation: receipt timeout txHash=%s sponsorshipActive=%s",
                    submitResult.txHash,
                    submitResult.sponsorshipActive,
                )
                return@withContext OnchainConfirmationOutcome.Failed(Exception("receipt poll timed out"))
            }

            outcomeFromReceipt(submitResult, receipt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "EASAttestation: on-chain confirmation failed")
            OnchainConfirmationOutcome.Failed(e)
        }
    }

    /**
     * One receipt fetch for a saved pending record. A transaction no RPC knows reads as
     * [PendingOnchainRecheckOutcome.Resubmit]; the RPC client returns null on errors too, so an outage
     * can also land there and resubmit (accepted double-attest risk).
     */
    internal suspend fun recheckPendingOnchainAttestation(
        pendingRecordJson: String,
    ): PendingOnchainRecheckOutcome = withContext(Dispatchers.IO) {
        val submitResult = submitResultFromPendingRecord(pendingRecordJson)
            ?: return@withContext PendingOnchainRecheckOutcome.StillPending
        try {
            val receipt = jsonRpcClient.getTransactionReceipt(submitResult.rpcUrls, submitResult.txHash)
            if (receipt == null) {
                val transaction = jsonRpcClient.getTransactionByHash(submitResult.rpcUrls, submitResult.txHash)
                return@withContext if (transaction == null) {
                    PendingOnchainRecheckOutcome.Resubmit
                } else {
                    PendingOnchainRecheckOutcome.StillPending
                }
            }
            when (val outcome = outcomeFromReceipt(submitResult, receipt)) {
                is OnchainConfirmationOutcome.Confirmed -> PendingOnchainRecheckOutcome.Confirmed(outcome.result)
                OnchainConfirmationOutcome.Reverted -> PendingOnchainRecheckOutcome.Resubmit
                // A mined success without a usable Attested log may still be ours; resubmitting
                // could double-attest, so the record stays for a later look.
                is OnchainConfirmationOutcome.Failed -> PendingOnchainRecheckOutcome.StillPending
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "EASAttestation: pending re-check failed txHash=%s", submitResult.txHash)
            PendingOnchainRecheckOutcome.StillPending
        }
    }

    /** The submit result a pending record was built from; null when malformed or on an unknown chain. */
    private fun submitResultFromPendingRecord(pendingRecordJson: String): OnchainSubmitResult? {
        val record = runCatching { JSONObject(pendingRecordJson) }.getOrNull() ?: return null
        val txHash = record.optString("txHash").ifBlank { return null }
        val chainId = record.optString("chainId")
        val chainConfig = SUPPORTED_CHAINS.find { it.caip2Id == chainId } ?: return null
        return OnchainSubmitResult(
            txHash = txHash,
            easAddress = record.optString("contractAddress"),
            chainIdStr = chainConfig.caip2Id,
            rpcUrls = chainConfig.rpcUrls,
            submittedAt = record.optLong("submittedAt"),
            sponsorshipActive = record.optBoolean("gasSponsored"),
            onChainAttester = record.optString("attesterAddress"),
        )
    }

    /** The confirmed-record builder shared by the live confirmation and the pending re-check. */
    private suspend fun outcomeFromReceipt(
        submitResult: OnchainSubmitResult,
        receipt: JSONObject,
    ): OnchainConfirmationOutcome {
        if (receipt.optString("status") != "0x1") {
            return OnchainConfirmationOutcome.Reverted
        }

        val logs = receipt.optJSONArray("logs")
            ?: return OnchainConfirmationOutcome.Failed(Exception("Receipt missing logs"))
        val attestationUid = jsonRpcClient.parseAttestedUid(logs, submitResult.easAddress)
            ?: return OnchainConfirmationOutcome.Failed(Exception("Attested event not found in receipt logs"))

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

        return OnchainConfirmationOutcome.Confirmed(
            LocationProtocolAttestationResult(
                uid = attestationUid,
                attesterAddress = submitResult.onChainAttester,
                timestamp = blockTimestamp * 1000L,
                offchainPayloadJson = attestationJsonObj.toString(),
            )
        )
    }

    internal suspend fun pollForReceipt(
        rpcUrls: List<String>,
        txHash: String,
        sponsorshipActive: Boolean,
    ): JSONObject? {
        if (sponsorshipActive) {
            repeat(3) {
                val receipt = jsonRpcClient.getTransactionReceipt(rpcUrls, txHash)
                if (receipt != null) return receipt
                delay(500L)
            }
            return null
        }

        repeat(RECEIPT_POLL_MAX_ATTEMPTS) {
            val receipt = jsonRpcClient.getTransactionReceipt(rpcUrls, txHash)
            if (receipt != null) return receipt
            delay(POLL_INTERVAL_MS)
        }
        return null
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
            attesterAddress = submitResult.onChainAttester,
            timestamp = submitResult.submittedAt,
            offchainPayloadJson = attestationJsonObj.toString(),
        )
    }

    /**
     * The wallet identity's chain, looked up in [SUPPORTED_CHAINS]. Both attestation
     * legs resolve through here so they sign for the same chain.
     */
    internal fun resolveSelectedChain(): ChainConfig {
        val identity = walletConnector.getIdentity() ?: throw Exception("Wallet identity missing")
        val chainConfig = SUPPORTED_CHAINS.find { it.caip2Id == identity.chainId }
            ?: throw Exception("Unsupported chain config")
        return chainConfig
    }

    private suspend fun resolveEasVersion(bridge: LPBridgeMessenger, chainConfig: ChainConfig): String {
        return easVersionResolver.resolve(chainConfig) {
            val response = bridge.invokeMethod(
                "get-eas-chain-params",
                mapOf("chainId" to chainConfig.caip2Id),
            ) as? Map<*, *>
            val easAddress = response?.get("easAddress") as? String
            val tableVersion = response?.get("easVersion") as? String
            if (easAddress == null || tableVersion == null) {
                throw LPBridgeException("get-eas-chain-params returned invalid response")
            }
            easAddress to tableVersion
        }
    }

    private fun canonicalAttesterAddress(): String = walletSigner.address

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
            attesterAddress = map["attesterAddress"]?.toString().orEmpty(),
            timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            offchainPayloadJson = map["offchainPayloadJson"]?.toString().orEmpty(),
        )
    }
}

private fun WalletConnector.sponsorshipActive(): Boolean =
    (this as? WalletCapabilityProvider)?.isSponsorshipActive == true
