package org.witness.proofmode.plugins.wallet.infra.config

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletChainPolicyTest {

    private val ethereum = "eip155:1"
    private val arbitrumOne = "eip155:42161"
    private val base = "eip155:8453"
    private val arbitrumSepolia = "eip155:421614"

    @Test
    fun resolve_keepsCandidateWithoutPersist_whenOffOrUnknownOrEmptyOrInSet() {
        // (case name, sponsorshipOn, sponsored set, stored chain)
        data class Case(val name: String, val sponsorshipOn: Boolean, val set: List<String>?, val stored: String)
        listOf(
            Case("sponsorship off", false, listOf(WalletChainPolicy.DEFAULT_CHAIN_ID, arbitrumSepolia), base),
            Case("set not yet known", true, null, base),
            Case("set known but empty", true, emptyList(), base),
            Case("candidate in set", true, listOf(WalletChainPolicy.DEFAULT_CHAIN_ID, ethereum), ethereum),
        ).forEach { case ->
            val result = WalletChainPolicy.resolveSessionChainId(
                storedChainId = case.stored,
                sponsorshipOn = case.sponsorshipOn,
                sponsoredChains = case.set,
            )
            assertEquals("case: ${case.name}", case.stored, result)
        }
    }

    @Test
    fun resolve_outsideSet_remapsToSepoliaElseFirstInOrder_andPersists() {
        // (case name, stored chain, sponsored set, expected chain)
        data class Case(val name: String, val stored: String, val set: List<String>, val expected: String)
        listOf(
            Case(
                "Sepolia in a larger set",
                arbitrumOne,
                listOf(ethereum, base, WalletChainPolicy.DEFAULT_CHAIN_ID, arbitrumSepolia),
                WalletChainPolicy.DEFAULT_CHAIN_ID,
            ),
            Case("no Sepolia: first in list order", arbitrumOne, listOf(base, ethereum), base),
        ).forEach { case ->
            val result = WalletChainPolicy.resolveSessionChainId(
                storedChainId = case.stored,
                sponsorshipOn = true,
                sponsoredChains = case.set,
            )
            assertEquals("case: ${case.name}", case.expected, result)
        }
    }

    @Test
    fun resolve_emptyStore_usesDefaultChainIdParameter_remapsOnlyOutsideSet_neverPersists() {
        // (case name, sponsored set, default chain, expected chain). Nothing is stored, so the
        // default is the candidate and the remap stays in memory.
        data class Case(val name: String, val set: List<String>, val default: String, val expected: String)
        listOf(
            Case("default in set", listOf(WalletChainPolicy.DEFAULT_CHAIN_ID, base), base, base),
            Case("default outside set", listOf(base), ethereum, base),
        ).forEach { case ->
            val result = WalletChainPolicy.resolveSessionChainId(
                storedChainId = null,
                sponsorshipOn = true,
                sponsoredChains = case.set,
                defaultChainId = case.default,
            )
            assertEquals("case: ${case.name}", case.expected, result)
        }
    }
}
