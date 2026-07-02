package org.witness.proofmode.cid

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CidTypesTest {
    @Test
    fun cidOptions_default_matchesV1Contract() {
        assertEquals(262_144, CidOptions.DEFAULT.chunkSize)
        assertEquals(1, CidOptions.DEFAULT.cidVersion)
        assertEquals(true, CidOptions.DEFAULT.rawLeaves)
        assertEquals(false, CidOptions.DEFAULT.wrapWithDirectory)
        assertEquals(262_144L, CidOptions.DEFAULT.shardThreshold)
    }

    @Test
    fun proofSetCidResult_includesTsizes() {
        val result = ProofSetCidResult(
            rootCid = "bafyTEST",
            files = mapOf("a.proof.csv" to "bafkLEAF"),
            tsizes = mapOf("a.proof.csv" to 42L),
        )
        val json = Json.encodeToString(result)
        assertEquals(result, Json.decodeFromString<ProofSetCidResult>(json))
    }

    @Test
    fun namedLeafCid_holdsPrecomputedLeafFields() {
        val leaf = NamedLeafCid(name = "hash123", leafCid = "bafkX", tsize = 99L)
        assertEquals("hash123", leaf.name)
        assertEquals("bafkX", leaf.leafCid)
        assertEquals(99L, leaf.tsize)
    }
}
