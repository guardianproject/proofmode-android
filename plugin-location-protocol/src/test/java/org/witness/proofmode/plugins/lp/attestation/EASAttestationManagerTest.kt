package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender
import org.witness.proofmode.plugins.wallet.infra.model.WalletCapabilities
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EASAttestationManagerTest {

    @Test
    fun pollForReceipt_sponsoredPath_stopsAfterFirstReceipt() = runTest {
        val rpcClient = EthJsonRpcClient(
            clientWithInterceptor { """{"jsonrpc":"2.0","id":1,"result":{"status":"0x1","logs":[]}}""" }
        )
        val manager = EASAttestationManager.forTesting(
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
            chainDisplayName = "Sepolia",
        )

        assertNotNull(receipt)
    }

    @Test
    fun pollForReceipt_sponsoredPath_returnsNullAfterThreeAttempts() = runTest {
        val attempts = AtomicInteger(0)
        val rpcClient = EthJsonRpcClient(
            clientWithInterceptor {
                attempts.incrementAndGet()
                """{"jsonrpc":"2.0","id":1,"result":null}"""
            }
        )
        val manager = EASAttestationManager.forTesting(
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
            chainDisplayName = "Sepolia",
        )

        assertNull(receipt)
        assertEquals(3, attempts.get())
    }

    @Test
    fun submitOnchainLocationAttestation_skipsEstimateGasWhenSponsorshipActive() = runTest {
        val walletSigner = mock<WalletSigner>()
        whenever(walletSigner.address).thenReturn("0xAttester")

        val walletConnector = mock<WalletConnector>()
        val capabilityProvider = object : WalletConnector by walletConnector, WalletCapabilityProvider {
            override fun getCapabilities() = WalletCapabilities(supportsSendTransaction = true)
            override val isSponsorshipActive: Boolean = true
        }
        whenever(capabilityProvider.getIdentity()).thenReturn(
            WalletIdentity(address = "0xAttester", chainId = "eip155:11155111")
        )

        val transactionSender = mock<WalletTransactionSender>()
        whenever(transactionSender.sendTransaction(any())).thenReturn(mapOf("txHash" to "0xhash"))

        val recordedMethods = mutableListOf<String>()
        val schemaRegisteredBody =
            """{"jsonrpc":"2.0","id":1,"result":"0x00000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000001"}"""
        val rpcClient = EthJsonRpcClient(
            clientWithInterceptor { request ->
                val bodyText = request.body?.let { body ->
                    val buffer = okio.Buffer()
                    body.writeTo(buffer)
                    buffer.readUtf8()
                }.orEmpty()
                val method = JSONObject(bodyText).optString("method")
                recordedMethods += method
                schemaRegisteredBody
            }
        )

        val bridge = mock<org.witness.proofmode.plugins.lp.bridge.LPBridgeMessenger>()
        whenever(bridge.awaitReady()).thenReturn(Unit)
        whenever(bridge.invokeMethod(eq("build-eas-onchain-data"), any())).thenReturn(
            mapOf(
                "txData" to "0xdeadbeef",
                "schemaId" to "0xde87be8a0b537bf996e1a623a3f98aaa51aad3b3362b56c3fac10fd81582ba81",
                "easAddress" to "0xeas",
                "schemaRegistryAddress" to "0xA7b39296258348C78294F95B872b282326A97BDF",
            )
        )

        val manager = EASAttestationManager.forTesting(
            bridgeProvider = { bridge },
            walletSigner = walletSigner,
            transactionSender = transactionSender,
            walletConnector = capabilityProvider,
            jsonRpcClient = rpcClient,
        )

        val payload = LocationProtocolPayload(
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

        val result = manager.submitOnchainLocationAttestation(payload)
        assertTrue(result.isSuccess)
        assertTrue(recordedMethods.none { it == "eth_estimateGas" })
    }

    private fun clientWithInterceptor(body: String): OkHttpClient =
        clientWithInterceptor { body }

    private fun clientWithInterceptor(bodyProvider: (okhttp3.Request) -> String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val responseBody = bodyProvider(chain.request())
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(responseBody.toResponseBody())
                        .build()
                }
            )
            .build()
}
