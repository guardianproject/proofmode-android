package org.witness.proofmode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.plugins.ipfscid.IpfsCidSidecar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ProofOverviewArtifactSummariesTest {

    @Test
    fun chainDisplayName_resolvesKnownChain() {
        assertEquals(
            "Arbitrum One",
            ProofOverviewArtifactSummaries.chainDisplayName("""{"chainId":"eip155:42161"}"""),
        )
    }

    @Test
    fun chainDisplayName_nullWhenNoChain() {
        assertNull(ProofOverviewArtifactSummaries.chainDisplayName("not json at all"))
        assertNull(ProofOverviewArtifactSummaries.chainDisplayName("""{"domain":{"chainId":1},"message":{}}"""))
        assertNull(ProofOverviewArtifactSummaries.chainDisplayName("""{"chainId":"eip155:999999999"}"""))
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
    fun transactionUrl_resolvesWithoutAttestationUid() {
        val json = """{"status":"pending_broadcast","txHash":"0xhash","chainId":"eip155:42161"}"""
        assertEquals(
            "https://arbiscan.io/tx/0xhash",
            ProofOverviewArtifactSummaries.transactionUrlForArtifact(json),
        )
        assertNull(ProofOverviewArtifactSummaries.easScanUrlForArtifact(json))

        val padded = """{"txHash":" 0xabc ","chainId":"eip155:42161"}"""
        assertEquals(
            "https://arbiscan.io/tx/0xabc",
            ProofOverviewArtifactSummaries.transactionUrlForArtifact(padded),
        )
    }

    @Test
    fun transactionUrl_nullWhenUnresolvable() {
        listOf(
            """{"txHash":"","chainId":"eip155:42161"}""",
            """{"txHash":"   ","chainId":"eip155:42161"}""",
            """{"chainId":"eip155:42161"}""",
            """{"txHash":"0xhash","chainId":"eip155:999999999"}""",
            """{"txHash":"0xhash"}""",
            "not json at all",
        ).forEach { json ->
            assertNull(json, ProofOverviewArtifactSummaries.transactionUrlForArtifact(json))
        }
    }

    @Test
    fun formatArtifactJson_prettyPrintsAndFallsBackToRaw() {
        val raw = """{"a":1,"b":"two"}"""
        val formatted = ProofOverviewArtifactSummaries.formatArtifactJson(raw)
        assert(formatted.contains("\n"))
        assert(formatted.contains("\"a\""))

        val unparseable = "not json at all"
        assertEquals(unparseable, ProofOverviewArtifactSummaries.formatArtifactJson(unparseable))
    }

    @Test
    fun rootCidForArtifact_nullWhenAbsentBlankOrUnparseable() {
        listOf(
            """{"rootCid":"   "}""",
            """{"files":{}}""",
            "not json",
            """{"rootCid":null}""",
        ).forEach { json ->
            assertNull(json, ProofOverviewArtifactSummaries.rootCidForArtifact(json))
        }
    }

    @Test
    fun rootCidForArtifact_readsRealSidecar() {
        val sidecar = IpfsCidSidecar.encode(
            rootCid = "bafyROOT",
            files = mapOf("proof.json" to "bafyFILE"),
            computedAtMs = 1L,
        ).toString(Charsets.UTF_8)

        assertEquals("bafyROOT", ProofOverviewArtifactSummaries.rootCidForArtifact(sidecar))
    }
}
