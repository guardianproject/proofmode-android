package org.witness.proofmode.plugins.ipfscid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpfsCidSidecarTest {
    @Test
    fun sidecarBasename_appendsSuffix() {
        assertEquals("abc123.ipfs-cids.json", IpfsCidSidecar.sidecarBasename("abc123"))
    }

    @Test
    fun isLateSnapshotBasename_detectsDebugSnapshots() {
        assertTrue(IpfsCidSidecar.isLateSnapshotBasename("abc123.ipfs-cids.late-999.json"))
        assertFalse(IpfsCidSidecar.isLateSnapshotBasename("abc123.ipfs-cids.json"))
    }

    @Test
    fun encode_includesVersionRootFilesOptions() {
        val bytes = IpfsCidSidecar.encode(
            rootCid = "bafyROOT",
            files = mapOf("h.proof.csv" to "bafkLEAF"),
            computedAtMs = 1718820000000L,
        )
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"version\":1"))
        assertTrue(text.contains("bafyROOT"))
        assertTrue(text.contains("chunkSize"))
        assertTrue(text.contains("1718820000000"))
    }

    @Test
    fun encode_includesTsizesMap() {
        val bytes = IpfsCidSidecar.encode(
            rootCid = "bafyROOT",
            files = mapOf("h" to "bafkMEDIA", "h.proof.csv" to "bafkCSV"),
            tsizes = mapOf("h" to 1048576L, "h.proof.csv" to 512L),
            computedAtMs = 1718820000000L,
        )
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"tsizes\""))
        assertTrue(text.contains("1048576"))
    }

    @Test
    fun decode_roundTripsFilesAndTsizes() {
        val original = IpfsCidSidecar.encode(
            rootCid = "bafyROOT",
            files = mapOf("h" to "bafkMEDIA", "h.proof.csv" to "bafkCSV"),
            tsizes = mapOf("h" to 1048576L, "h.proof.csv" to 512L),
            computedAtMs = 1718820000000L,
        )
        val parsed = IpfsCidSidecar.decode(original)
        assertEquals("bafyROOT", parsed.rootCid)
        assertEquals(mapOf("h" to "bafkMEDIA", "h.proof.csv" to "bafkCSV"), parsed.files)
        assertEquals(mapOf("h" to 1048576L, "h.proof.csv" to 512L), parsed.tsizes)
    }
}
