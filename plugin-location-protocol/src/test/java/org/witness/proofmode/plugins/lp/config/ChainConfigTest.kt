package org.witness.proofmode.plugins.lp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainConfigTest {

    @Test
    fun `SUPPORTED_CHAINS lists mainnets before testnets`() {
        val ids = SUPPORTED_CHAINS.map { it.caip2Id }
        val mainnets = listOf("eip155:1", "eip155:42161", "eip155:8453", "eip155:10", "eip155:534352", "eip155:57073", "eip155:42220")
        val testnets = listOf("eip155:11155111", "eip155:421614", "eip155:11155420", "eip155:84532", "eip155:763373", "eip155:11142220")
        assertEquals(mainnets + testnets, ids)
    }

    @Test
    fun `all non-null URL templates contain address placeholder`() {
        SUPPORTED_CHAINS.forEach { chain ->
            assertTrue(
                "Explorer URL for ${chain.displayName} has the wrong shape",
                chain.explorerAddressUrl.endsWith("/address/{address}"),
            )
            chain.easScanAddressUrl?.let {
                assertTrue("EAS URL for ${chain.displayName} has the wrong shape", it.endsWith("/address/{address}"))
            }
        }
    }

    @Test
    fun `address-derived URLs`() {
        val eth = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:1" }
        val arb = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:42161" }
        val uid = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"

        assertEquals("https://etherscan.io/address/0xABCDEF1234567890", eth.explorerUrl("0xABCDEF1234567890"))
        assertEquals("https://arbitrum.easscan.org/address/0x1234", arb.easScanUrl("0x1234"))
        assertEquals("https://arbitrum.easscan.org/attestation/view/$uid", arb.easScanAttestationUrl(uid))
        assertEquals("https://etherscan.io/tx/0xabc", eth.explorerTransactionUrl("0xabc"))
        assertEquals("https://arbiscan.io/tx/0xabc", arb.explorerTransactionUrl("0xabc"))
    }

    @Test
    fun `easScan derivations are null when the EAS Scan template is null`() {
        val noEasScan = chainWithTemplates(explorer = EXPLORER_TEMPLATE, easScan = null)
        val cases = listOf<Pair<String, () -> String?>>(
            "easScanUrl without EAS Scan template" to { noEasScan.easScanUrl("0x1") },
            "easScanAttestationUrl without EAS Scan template" to { noEasScan.easScanAttestationUrl("0xuid") },
        )
        for ((label, derive) in cases) {
            assertNull(label, derive())
        }
    }

    @Test
    fun `link visibility follows which templates the chain has`() {
        val eth = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:1" }
        val cases = listOf(
            Triple("both templates", eth, WalletExplorerLinkVisibility(transactions = true, attestations = true)),
            Triple(
                "EAS Scan template null",
                chainWithTemplates(explorer = EXPLORER_TEMPLATE, easScan = null),
                WalletExplorerLinkVisibility(transactions = true, attestations = false),
            ),
            Triple(
                "unresolved chain",
                null,
                WalletExplorerLinkVisibility(transactions = false, attestations = false),
            ),
        )
        for ((label, chain, expected) in cases) {
            assertEquals(label, expected, walletExplorerLinkVisibility(chain))
        }
    }

    private fun chainWithTemplates(explorer: String, easScan: String?) = ChainConfig(
        displayName = "Synthetic",
        caip2Id = "eip155:999999",
        explorerAddressUrl = explorer,
        easScanAddressUrl = easScan,
        rpcUrls = emptyList(),
    )

    private companion object {
        const val EXPLORER_TEMPLATE = "https://explorer.example/address/{address}"
        const val EAS_TEMPLATE = "https://easscan.example/address/{address}"
    }
}
