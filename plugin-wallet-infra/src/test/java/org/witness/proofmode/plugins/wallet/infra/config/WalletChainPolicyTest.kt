package org.witness.proofmode.plugins.wallet.infra.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletChainPolicyTest {

    @Test
    fun defaultChainId_isSepolia() {
        assertEquals("eip155:11155111", WalletChainPolicy.DEFAULT_CHAIN_ID)
    }

    @Test
    fun leftoverMainnets_areFullCaip2Only() {
        assertEquals(
            setOf("eip155:1", "eip155:42161", "eip155:8453"),
            WalletChainPolicy.LEFTOVER_MAINNET_CHAIN_IDS,
        )
        assertFalse(WalletChainPolicy.isLeftoverMainnet("42161"))
        assertFalse(WalletChainPolicy.isLeftoverMainnet("8453"))
        assertFalse(WalletChainPolicy.isLeftoverMainnet("1"))
        assertTrue(WalletChainPolicy.isLeftoverMainnet("eip155:1"))
        assertTrue(WalletChainPolicy.isLeftoverMainnet("eip155:42161"))
        assertTrue(WalletChainPolicy.isLeftoverMainnet("eip155:8453"))
    }

    @Test
    fun sponsoredVisible_isSepoliaThenArbitrumSepolia() {
        assertEquals(
            listOf("eip155:11155111", "eip155:421614"),
            WalletChainPolicy.SPONSORED_VISIBLE_CHAIN_IDS,
        )
    }

    @Test
    fun resolve_sponsorshipOn_remapsEachLeftoverAndRequestsPersist() {
        listOf("eip155:1", "eip155:42161", "eip155:8453").forEach { leftover ->
            val result = WalletChainPolicy.resolveSessionChainId(
                storedChainId = leftover,
                sponsorshipOn = true,
            )
            assertEquals("eip155:11155111", result.chainId)
            assertTrue(result.persistRemap)
        }
    }

    @Test
    fun resolve_sponsorshipOff_preservesLeftover() {
        val result = WalletChainPolicy.resolveSessionChainId(
            storedChainId = "eip155:1",
            sponsorshipOn = false,
        )
        assertEquals("eip155:1", result.chainId)
        assertFalse(result.persistRemap)
    }

    @Test
    fun resolve_sponsorshipOn_keepsSepoliaAndArbitrumSepolia() {
        val sepolia = WalletChainPolicy.resolveSessionChainId("eip155:11155111", sponsorshipOn = true)
        assertEquals("eip155:11155111", sepolia.chainId)
        assertFalse(sepolia.persistRemap)

        val arbSepolia = WalletChainPolicy.resolveSessionChainId("eip155:421614", sponsorshipOn = true)
        assertEquals("eip155:421614", arbSepolia.chainId)
        assertFalse(arbSepolia.persistRemap)
    }

    @Test
    fun resolve_emptyStore_usesDefault_withoutPersist() {
        val result = WalletChainPolicy.resolveSessionChainId(
            storedChainId = null,
            sponsorshipOn = true,
        )
        assertEquals("eip155:11155111", result.chainId)
        assertFalse(result.persistRemap)
    }

    @Test
    fun resolve_emptyStore_explicitEthereumDefault_sponsorshipOn_usesSepoliaInMemoryWithoutPersist() {
        val result = WalletChainPolicy.resolveSessionChainId(
            storedChainId = null,
            sponsorshipOn = true,
            defaultChainId = "eip155:1",
        )
        assertEquals("eip155:11155111", result.chainId)
        assertFalse(result.persistRemap)
    }
}
