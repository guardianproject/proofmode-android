package org.witness.proofmode.plugins.lp.attestation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EASAttestationAddressTest {

    @Test
    fun pendingArtifact_usesCanonicalAttesterAddress_withoutSignerAddress() {
        val walletSigner = mock<WalletSigner>()
        whenever(walletSigner.address).thenReturn("0xABC")

        val manager = EASAttestationManager.forTesting(
            bridgeProvider = { throw UnsupportedOperationException() },
            walletSigner = walletSigner,
            transactionSender = mock(),
            walletConnector = mock(),
            jsonRpcClient = EthJsonRpcClient(),
        )

        val json = manager.buildPendingAttestationResult(
            OnchainSubmitResult(
                txHash = "0x1",
                schemaId = "0xschema",
                easAddress = "0xeas",
                chainIdStr = "eip155:1",
                rpcUrls = emptyList(),
                chainDisplayName = "Mainnet",
                submittedAt = 1L,
                sponsorshipActive = true,
                onChainAttester = walletSigner.address,
            )
        ).offchainPayloadJson

        val parsed = JSONObject(json)
        assertEquals("0xABC", parsed.getString("attesterAddress"))
        assertFalse(parsed.has("signerAddress"))
    }
}
