package org.witness.proofmode.plugins.ipfscid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidecarReaderTest {
    @Test fun readNormalizesLegacyBareMediaKey_toExtensionQualified() {
        val hash = "abc123"
        val json = IpfsCidSidecar.encode(
            rootCid = "bafy",
            files = mapOf(hash to "bafkLEGACY", "$hash.proof.csv" to "bafkCSV"),
            tsizes = mapOf(hash to 99L, "$hash.proof.csv" to 10L),
            computedAtMs = 1L,
        )
        val snapshot = SidecarReader.decodeAndNormalize(json, proofSetHash = hash, mediaMimeType = "image/jpeg")
        assertEquals("bafkLEGACY", snapshot.files["$hash.jpg"])
        assertEquals(99L, snapshot.tsizes["$hash.jpg"])
        assertNull(snapshot.files[hash])
    }
}
