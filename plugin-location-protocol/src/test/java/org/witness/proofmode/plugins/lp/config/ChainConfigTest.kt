package org.witness.proofmode.plugins.lp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainConfigTest {

    @Test
    fun `SUPPORTED_CHAINS contains all 5 networks`() {
        assertEquals(5, SUPPORTED_CHAINS.size)
    }

    @Test
    fun `each chain has unique caip2Id`() {
        val ids = SUPPORTED_CHAINS.map { it.caip2Id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `ethereum mainnet has correct caip2Id`() {
        val eth = SUPPORTED_CHAINS.first { it.displayName == "Ethereum Mainnet" }
        assertEquals("eip155:1", eth.caip2Id)
    }

    @Test
    fun `explorerUrl substitutes address correctly`() {
        val eth = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:1" }
        val url = eth.explorerUrl("0xABCDEF1234567890")
        assertEquals("https://etherscan.io/address/0xABCDEF1234567890", url)
    }

    @Test
    fun `easScanUrl substitutes address correctly`() {
        val arb = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:42161" }
        val url = arb.easScanUrl("0x1234")
        assertEquals("https://arbitrum.easscan.org/address/0x1234", url)
    }

    @Test
    fun `all explorer URLs contain address placeholder`() {
        SUPPORTED_CHAINS.forEach { chain ->
            assertTrue(
                "Explorer URL for ${chain.displayName} missing placeholder",
                chain.explorerAddressUrl.contains("{address}")
            )
            assertTrue(
                "EAS URL for ${chain.displayName} missing placeholder",
                chain.easScanAddressUrl.contains("{address}")
            )
        }
    }

    @Test
    fun `all chains have non-blank display names`() {
        SUPPORTED_CHAINS.forEach { chain ->
            assertTrue(chain.displayName.isNotBlank())
        }
    }

    @Test
    fun `easScanAttestationUrl derives attestation view URL from address template`() {
        val arb = SUPPORTED_CHAINS.first { it.caip2Id == "eip155:42161" }
        val uid = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        val url = arb.easScanAttestationUrl(uid)
        assertEquals("https://arbitrum.easscan.org/attestation/view/$uid", url)
    }

    @Test
    fun `easScanAttestationUrl works for all supported chains`() {
        val uid = "0xtestuid"
        SUPPORTED_CHAINS.forEach { chain ->
            val url = chain.easScanAttestationUrl(uid)
            assertTrue(url.endsWith("/attestation/view/$uid"))
            assertTrue(url.startsWith(chain.easScanAddressUrl.substringBefore("/address/")))
        }
    }
}
