package org.witness.proofmode.plugins.ipfscid

import org.junit.Assert.assertEquals
import org.junit.Test

class ProofSetCidManifestTest {
    @Test fun composeByteBackedManifest_injectsExtensionQualifiedMediaLink() {
        val hash = "abc123"
        val entries = ProofSetCidManifest.composeByteBackedManifest(
            proofSetHash = hash,
            manifestMemberBasenames = listOf("$hash.proof.csv"),
            diskBytesByBasename = mapOf("$hash.proof.csv" to byteArrayOf(1, 2)),
            mediaBytes = byteArrayOf(9, 9),
            mediaMimeType = "image/jpeg",
            includeOts = false,
            includeNostr = false,
        )
        assertEquals("$hash.jpg", entries.first().name)
        assertEquals(2, entries.size)
    }

    @Test fun composeLeafBackedManifest_reusesCachedLeavesAndInjectsMedia() {
        val hash = "abc123"
        val sidecar = SidecarSnapshot(
            rootCid = "bafy",
            files = mapOf("$hash.jpg" to "bafkMEDIA", "$hash.proof.csv" to "bafkCSV"),
            tsizes = mapOf("$hash.jpg" to 100L, "$hash.proof.csv" to 50L),
        )
        val leaves = ProofSetCidManifest.composeLeafBackedManifest(
            proofSetHash = hash,
            manifestMemberBasenames = listOf("$hash.proof.csv"),
            sidecar = sidecar,
            newLeafBytesByBasename = emptyMap(),
            computeLeafFromBytes = { error("should not compute") },
        )
        assertEquals(2, leaves.size)
        assertEquals("bafkMEDIA", leaves.find { it.name == "$hash.jpg" }!!.leafCid)
    }
}
