package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.witness.proofmode.plugins.lp.attestation.FakeEthRpc.Companion.abiString
import org.witness.proofmode.plugins.lp.attestation.FakeEthRpc.Reply
import org.witness.proofmode.plugins.lp.bridge.LPBridgeMessenger
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EASAttestationManagerTest {

    // The manager shares the process-wide live-version cache, so one test's live version must not answer the next.
    @Before
    fun clearLiveVersions() = EasVersionResolver.processLiveVersions.clear()

    @Test
    fun pollForReceipt_sponsoredPath_stopsAfterFirstReceipt() = runTest {
        val rpcClient = EthJsonRpcClient(
            methodRoutedRpcClient { """{"status":"0x1","logs":[]}""" }
        )
        val manager = EASAttestationManager(
            bridgeProvider = { throw UnsupportedOperationException() },
            walletSigner = mock(),
            transactionSender = mock(),
            walletConnector = mock(),
            jsonRpcClient = rpcClient,
        )

        val receipt = manager.pollForReceipt(
            rpcUrls = listOf("https://rpc.test"),
            txHash = "0xabc",
            sponsorshipActive = true,
        )

        assertNotNull(receipt)
    }

    @Test
    fun pollForReceipt_sponsoredPath_returnsNullAfterThreeAttempts() = runTest {
        val attempts = AtomicInteger(0)
        val rpcClient = EthJsonRpcClient(
            methodRoutedRpcClient {
                attempts.incrementAndGet()
                "null"
            }
        )
        val manager = EASAttestationManager(
            bridgeProvider = { throw UnsupportedOperationException() },
            walletSigner = mock(),
            transactionSender = mock(),
            walletConnector = mock(),
            jsonRpcClient = rpcClient,
        )

        val receipt = manager.pollForReceipt(
            rpcUrls = listOf("https://rpc.test"),
            txHash = "0xabc",
            sponsorshipActive = true,
        )

        assertNull(receipt)
        assertEquals(3, attempts.get())
    }

    @Test
    fun pollForReceipt_selfFunded_returnsFirstReceiptWithoutMempoolLookup() = runTest {
        val methods = mutableListOf<String>()
        var receiptReads = 0
        val rpcClient = EthJsonRpcClient(
            methodRoutedRpcClient { method ->
                methods += method
                when (method) {
                    "eth_getTransactionReceipt" ->
                        if (++receiptReads == 3) """{"status":"0x1","logs":[]}""" else "null"
                    else -> "null"
                }
            }
        )
        val manager = EASAttestationManager(
            bridgeProvider = { throw UnsupportedOperationException() },
            walletSigner = mock(),
            transactionSender = mock(),
            walletConnector = mock(),
            jsonRpcClient = rpcClient,
        )

        val receipt = manager.pollForReceipt(
            rpcUrls = listOf("https://rpc.test"),
            txHash = "0xabc",
            sponsorshipActive = false,
        )

        assertNotNull(receipt)
        assertEquals(3, receiptReads)
        assertTrue(methods.none { it == "eth_getTransactionByHash" })
    }

    @Test
    fun pollForReceipt_selfFunded_neverReceipt_returnsNullAfter120Polls() = runTest {
        val methods = mutableListOf<String>()
        val rpcClient = EthJsonRpcClient(
            methodRoutedRpcClient { method ->
                methods += method
                "null"
            }
        )
        val manager = EASAttestationManager(
            bridgeProvider = { throw UnsupportedOperationException() },
            walletSigner = mock(),
            transactionSender = mock(),
            walletConnector = mock(),
            jsonRpcClient = rpcClient,
        )

        val receipt = manager.pollForReceipt(
            rpcUrls = listOf("https://rpc.test"),
            txHash = "0xabc",
            sponsorshipActive = false,
        )

        assertNull(receipt)
        assertEquals(120, methods.count { it == "eth_getTransactionReceipt" })
        assertTrue(methods.none { it == "eth_getTransactionByHash" })
    }

    @Test
    fun confirmOnchain_returnsTypedOutcomes() = runTest {
        val uidWord = "ab".repeat(32)
        val cases = listOf(
            "null receipt" to "null",
            "reverted" to receiptJson("0x0", EAS_ADDRESS, uidWord),
            "no Attested log" to receiptJson("0x1", EAS_ADDRESS, attestedUidWord = null),
            "good receipt" to receiptJson("0x1", EAS_ADDRESS, uidWord),
        )
        val outcomes = cases.associate { (label, receipt) ->
            val rpc = methodRoutedRpcClient { method ->
                when (method) {
                    "eth_getTransactionReceipt" -> receipt
                    "eth_getBlockByNumber" -> """{"timestamp":"0x64"}"""
                    else -> "null"
                }
            }
            val manager = EASAttestationManager(
                bridgeProvider = { throw UnsupportedOperationException() },
                walletSigner = mock(),
                transactionSender = mock(),
                walletConnector = mock(),
                jsonRpcClient = EthJsonRpcClient(rpc),
            )
            label to manager.confirmOnchainLocationAttestation(sponsoredSubmitResult())
        }

        assertTrue(outcomes["null receipt"] is OnchainConfirmationOutcome.Failed)
        assertEquals(OnchainConfirmationOutcome.Reverted, outcomes["reverted"])
        assertTrue(outcomes["no Attested log"] is OnchainConfirmationOutcome.Failed)
        val confirmed = outcomes["good receipt"] as OnchainConfirmationOutcome.Confirmed
        val json = JSONObject(confirmed.result.offchainPayloadJson)
        assertEquals(EASAttestationManager.STATUS_CONFIRMED, json.getString("status"))
        assertEquals("0x$uidWord", json.getString("attestationUid"))
        assertEquals("0xtx", json.getString("txHash"))
        assertEquals(100L, json.getLong("blockTimestamp"))
        assertEquals("0x$uidWord", confirmed.result.uid)
    }

    private fun sponsoredSubmitResult() = OnchainSubmitResult(
        txHash = "0xtx",
        easAddress = EAS_ADDRESS,
        chainIdStr = "eip155:11155111",
        rpcUrls = listOf("https://rpc.test"),
        submittedAt = 1L,
        sponsorshipActive = true,
        onChainAttester = "0xattester",
    )

    @Test
    fun submitOnchainLocationAttestation_skipsEstimateGasWhenSponsorshipActive() = runTest {
        val walletSigner = mock<WalletSigner>()
        whenever(walletSigner.address).thenReturn("0xAttester")

        val walletConnector = mock<WalletConnector>()
        val capabilityProvider = object : WalletConnector by walletConnector, WalletCapabilityProvider {
            override val isSponsorshipActive: Boolean = true
        }
        whenever(capabilityProvider.getIdentity()).thenReturn(
            WalletIdentity(address = "0xAttester", chainId = "eip155:11155111")
        )

        val transactionSender = mock<WalletTransactionSender>()
        whenever(transactionSender.sendTransaction(any())).thenReturn(mapOf("txHash" to "0xhash"))

        val recordedMethods = mutableListOf<String>()
        val rpcClient = EthJsonRpcClient(
            methodRoutedRpcClient { method ->
                recordedMethods += method
                "null"
            }
        )

        val bridge = mock<LPBridgeMessenger>()
        whenever(bridge.awaitReady()).thenReturn(Unit)
        whenever(bridge.invokeMethod(eq("build-eas-onchain-data"), any())).thenReturn(
            mapOf(
                "txData" to "0xdeadbeef",
                "easAddress" to "0xeas",
            )
        )

        val manager = EASAttestationManager(
            bridgeProvider = { bridge },
            walletSigner = walletSigner,
            transactionSender = transactionSender,
            walletConnector = capabilityProvider,
            jsonRpcClient = rpcClient,
        )

        val result = manager.submitOnchainLocationAttestation(testPayload())
        assertTrue(result.isSuccess)
        assertTrue(recordedMethods.none { it == "eth_estimateGas" })
    }

    @Test
    fun createOffchain_passesResolvedVersionToBridge() = runTest {
        val cases = mapOf(
            "live reply" to (Reply.Result(abiString("1.4.1-beta.3")) to "1.4.1-beta.3"),
            "503 reply falls back to table version" to (Reply.Http(503) to "1.4.1-beta.1"),
        )
        for ((label, case) in cases) {
            val (rpcReply, expectedVersion) = case
            EasVersionResolver.processLiveVersions.clear()
            val walletSigner = mock<WalletSigner>()
            whenever(walletSigner.address).thenReturn("0xAttester")
            whenever(walletSigner.signTypedData(any())).thenReturn("0xsig")
            val bridge = offchainBridge(tableVersion = "1.4.1-beta.1")
            val rpc = FakeEthRpc { rpcReply }
            val manager = managerFor(
                identityChainId = "eip155:763373",
                walletSigner = walletSigner,
                bridge = bridge,
                rpcClient = rpc.okHttpClient,
            )

            val result = manager.createOffchainLocationAttestation(testPayload())

            assertTrue(label, result.isSuccess)
            val paramsArgs = argumentCaptor<Map<String, Any?>>()
            val typedDataArgs = argumentCaptor<Map<String, Any?>>()
            verify(bridge).invokeMethod(eq("get-eas-chain-params"), paramsArgs.capture())
            verify(bridge).invokeMethod(eq("build-eas-typed-data"), typedDataArgs.capture())
            assertEquals(label, mapOf<String, Any?>("chainId" to "eip155:763373"), paramsArgs.firstValue)
            assertEquals(label, "eip155:763373", typedDataArgs.firstValue["chainId"])
            assertEquals(label, expectedVersion, typedDataArgs.firstValue["easVersion"])
            assertNotNull(label, typedDataArgs.firstValue["payload"])
            assertTrue(label, rpc.calls.all { it.to == EAS_ADDRESS })
            verify(walletSigner).signTypedData(any())
        }
    }

    // The table also covers the onchain leg: it resolves the chain through the same helper.
    @Test
    fun createOffchain_unsupportedIdentityChain_failsBeforeSigning() = runTest {
        val cases = listOf(
            ChainFailureCase("offchain, null identity", onchain = false, null, "Wallet identity missing"),
            ChainFailureCase("offchain, unsupported chain", onchain = false, "eip155:999999", "Unsupported chain config"),
            ChainFailureCase("onchain, unsupported chain", onchain = true, "eip155:999999", "Unsupported chain config"),
        )
        for ((label, onchain, identityChainId, expectedMessage) in cases) {
            val walletSigner = mock<WalletSigner>()
            val bridge = mock<LPBridgeMessenger>()
            whenever(bridge.awaitReady()).thenReturn(Unit)
            val manager = managerFor(
                identityChainId = identityChainId,
                walletSigner = walletSigner,
                bridge = bridge,
            )

            val result = if (onchain) {
                manager.submitOnchainLocationAttestation(testPayload())
            } else {
                manager.createOffchainLocationAttestation(testPayload())
            }

            assertTrue(label, result.isFailure)
            assertEquals(label, expectedMessage, result.exceptionOrNull()?.message)
            if (onchain) {
                verify(bridge, never()).invokeMethod(eq("build-eas-onchain-data"), any())
            } else {
                verify(walletSigner, never()).signTypedData(any())
            }
        }
    }

    private suspend fun offchainBridge(tableVersion: String): LPBridgeMessenger {
        val bridge = mock<LPBridgeMessenger>()
        whenever(bridge.awaitReady()).thenReturn(Unit)
        whenever(bridge.invokeMethod(eq("get-eas-chain-params"), any())).thenReturn(
            mapOf("easAddress" to EAS_ADDRESS, "easVersion" to tableVersion)
        )
        whenever(bridge.invokeMethod(eq("build-eas-typed-data"), any())).thenReturn("{\"domain\":{}}")
        whenever(bridge.invokeMethod(eq("create-offchain-attestation"), any())).thenReturn(
            mapOf("uid" to "0xuid", "attesterAddress" to "0xAttester")
        )
        return bridge
    }

    private fun managerFor(
        identityChainId: String?,
        walletSigner: WalletSigner,
        bridge: LPBridgeMessenger,
        rpcClient: OkHttpClient = methodRoutedRpcClient { "{}" },
    ): EASAttestationManager {
        val walletConnector = mock<WalletConnector>()
        whenever(walletConnector.getIdentity()).thenReturn(
            identityChainId?.let { WalletIdentity(address = "0xAttester", chainId = it) }
        )
        return EASAttestationManager(
            bridgeProvider = { bridge },
            walletSigner = walletSigner,
            transactionSender = mock(),
            walletConnector = walletConnector,
            jsonRpcClient = EthJsonRpcClient(rpcClient),
        )
    }

    private fun testPayload() = LocationProtocolPayload(
        eventTimestamp = 1L,
        srs = "EPSG:4326",
        locationType = "point",
        location = "0,0",
        recipeType = emptyArray(),
        recipePayload = emptyArray(),
        mediaType = emptyArray(),
        mediaData = emptyArray(),
        memo = "",
    )

    private data class ChainFailureCase(
        val label: String,
        val onchain: Boolean,
        val identityChainId: String?,
        val expectedMessage: String,
    )

    private companion object {
        const val EAS_ADDRESS = TEST_EAS_ADDRESS
    }
}
