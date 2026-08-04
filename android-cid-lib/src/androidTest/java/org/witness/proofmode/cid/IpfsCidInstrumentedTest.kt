package org.witness.proofmode.cid

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IpfsCidInstrumentedTest {

    private fun hexDecode(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun loadFixture(): kotlinx.serialization.json.JsonObject {
        val text = javaClass.classLoader!!
            .getResourceAsStream("ipfs_cid_vectors.json")!!
            .bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun allLeafCases() {
        val cases = loadFixture()["cases"]!!.jsonArray
        for (element in cases) {
            val obj = element.jsonObject
            if (!obj.containsKey("expected_cid")) continue
            val id = obj["id"]!!.jsonPrimitive.content
            val bytes = when {
                obj.containsKey("bytes_utf8") ->
                    obj["bytes_utf8"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
                obj.containsKey("bytes_hex") ->
                    hexDecode(obj["bytes_hex"]!!.jsonPrimitive.content)
                else -> continue
            }
            val expected = obj["expected_cid"]!!.jsonPrimitive.content
            assertEquals(id, expected, CidLib.computeFileCid(bytes))
        }
    }

    @Test
    fun allProofSetCases() {
        val cases = loadFixture()["cases"]!!.jsonArray
        for (element in cases) {
            val obj = element.jsonObject
            if (!obj.containsKey("expected_root_cid")) continue
            val id = obj["id"]!!.jsonPrimitive.content
            val entries = obj["files"]!!.jsonArray.map { fileEl ->
                val fileObj = fileEl.jsonObject
                val name = fileObj["name"]!!.jsonPrimitive.content
                val bytes = when {
                    fileObj.containsKey("bytes_utf8") ->
                        fileObj["bytes_utf8"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
                    fileObj.containsKey("bytes_hex") ->
                        hexDecode(fileObj["bytes_hex"]!!.jsonPrimitive.content)
                    else -> ByteArray(0)
                }
                NamedBytes(name, bytes)
            }
            val result = CidLib.computeProofSetCid(entries)
            assertEquals(id, obj["expected_root_cid"]!!.jsonPrimitive.content, result.rootCid)
            val expectedFiles = obj["expected_files"]!!.jsonObject
            expectedFiles.forEach { (name, expectedEl) ->
                assertEquals("$id:$name", expectedEl.jsonPrimitive.content, result.files[name])
            }
        }
    }

    @Test
    fun proofsetLexOrder_orderIndependent() {
        val cases = loadFixture()["cases"]!!.jsonArray
        val flatCore = cases.first { it.jsonObject["id"]!!.jsonPrimitive.content == "proofset_flat_core" }
        val lexOrder = cases.first { it.jsonObject["id"]!!.jsonPrimitive.content == "proofset_lex_order" }
        val flatRoot = flatCore.jsonObject["expected_root_cid"]!!.jsonPrimitive.content
        val lexRoot = lexOrder.jsonObject["expected_root_cid"]!!.jsonPrimitive.content
        assertEquals(flatRoot, lexRoot)
    }
}
