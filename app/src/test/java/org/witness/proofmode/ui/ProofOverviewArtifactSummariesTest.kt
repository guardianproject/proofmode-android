package org.witness.proofmode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ProofOverviewArtifactSummariesTest {

    @Test
    fun cidSummary_usesRootCid() {
        val json = """{"rootCid":"bafyroot","files":{},"computedAtMs":1}"""
        assertEquals("bafyroot", ProofOverviewArtifactSummaries.cidCollapsedSummary(json))
    }

    @Test
    fun offchainSummary_parsesTopLevelFields() {
        val json = """
            {
              "attestationUid":"0xuidfull1234567890",
              "chainId":"eip155:42161",
              "attesterAddress":"0xAttesterABCDEF1234567890"
            }
        """.trimIndent()
        val summary = ProofOverviewArtifactSummaries.offchainCollapsedSummary(json)
        assertEquals("0xuidfull1…", summary.uidShort)
        assertEquals("Arbitrum One", summary.chainDisplayName)
        assertEquals("0xAtte…7890", summary.attesterShort)
    }

    @Test
    fun offchainSummary_fallsBackWhenUnparseable() {
        val json = """{"domain":{"chainId":1},"message":{}}"""
        val summary = ProofOverviewArtifactSummaries.offchainCollapsedSummary(json)
        assertNull(summary.uidShort)
        assertNull(summary.chainDisplayName)
        assertNull(summary.attesterShort)
    }

    @Test
    fun onchainSummary_truncatesUidAndResolvesChain() {
        val json = """
            {
              "attestationUid":"0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
              "chainId":"eip155:11155111",
              "status":"confirmed"
            }
        """.trimIndent()
        val summary = ProofOverviewArtifactSummaries.onchainCollapsedSummary(json)
        assertEquals("0xabcdef12…", summary.uidShort)
        assertEquals("Sepolia Testnet", summary.chainDisplayName)
    }

    @Test
    fun easScanUrl_resolvesFromOnchainJson() {
        val json = """
            {
              "attestationUid":"0xuid",
              "chainId":"eip155:42161"
            }
        """.trimIndent()
        val url = ProofOverviewArtifactSummaries.easScanUrlForArtifact(json)
        assertEquals("https://arbitrum.easscan.org/attestation/view/0xuid", url)
    }

    @Test
    fun formatArtifactJson_prettyPrints() {
        val raw = """{"a":1,"b":"two"}"""
        val formatted = ProofOverviewArtifactSummaries.formatArtifactJson(raw)
        assert(formatted.contains("\n"))
        assert(formatted.contains("\"a\""))
    }
}
