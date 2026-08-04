package org.witness.proofmode.cid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class IpfsCidVectorsStructureTest {
    @Test
    fun fixture_hasAllRequiredCaseIds() {
        val text = javaClass.classLoader!!.getResourceAsStream("ipfs_cid_vectors.json")!!
            .bufferedReader().use { it.readText() }
        val required = setOf(
            "leaf_small_utf8", "leaf_empty", "leaf_multi_chunk",
            "leaf_proof_csv_like", "proofset_flat_core", "proofset_with_sigs", "proofset_lex_order",
        )
        val cases = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        val ids = cases.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        required.forEach { id ->
            assertTrue("$id missing", ids.contains(id))
        }
    }
}
