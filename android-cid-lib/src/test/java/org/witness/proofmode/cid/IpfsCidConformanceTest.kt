package org.witness.proofmode.cid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNoException
import org.junit.Test

class IpfsCidConformanceTest {
    private data class LeafCase(val id: String, val bytes: ByteArray, val expected: String)

    private data class ProofSetCase(
        val id: String,
        val entries: List<NamedBytes>,
        val expectedRoot: String,
        val expectedFiles: Map<String, String>,
        val expectedTsizes: Map<String, Long> = emptyMap(),
    )

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

    private fun leafCases(): List<LeafCase> {
        val fixture = loadFixture()
        return fixture["cases"]!!.jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]!!.jsonPrimitive.content
            if (!obj.containsKey("expected_cid")) return@mapNotNull null
            val bytes = when {
                obj.containsKey("bytes_utf8") ->
                    obj["bytes_utf8"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
                obj.containsKey("bytes_hex") ->
                    hexDecode(obj["bytes_hex"]!!.jsonPrimitive.content)
                else -> return@mapNotNull null
            }
            LeafCase(id, bytes, obj["expected_cid"]!!.jsonPrimitive.content)
        }
    }

    private fun proofSetCases(): List<ProofSetCase> {
        val fixture = loadFixture()
        return fixture["cases"]!!.jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            if (!obj.containsKey("expected_root_cid")) return@mapNotNull null
            val id = obj["id"]!!.jsonPrimitive.content
            val files = obj["files"]!!.jsonArray.map { fileEl ->
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
            val expectedFiles = obj["expected_files"]!!.jsonObject.entries.associate {
                it.key to it.value.jsonPrimitive.content
            }
            val expectedTsizes = obj["expected_tsizes"]?.jsonObject?.entries?.associate {
                it.key to it.value.jsonPrimitive.content.toLong()
            } ?: emptyMap()
            ProofSetCase(
                id = id,
                entries = files,
                expectedRoot = obj["expected_root_cid"]!!.jsonPrimitive.content,
                expectedFiles = expectedFiles,
                expectedTsizes = expectedTsizes,
            )
        }
    }

    @Test
    fun leafCases_matchFixture_whenNativeAvailable() {
        try {
            for (c in leafCases()) {
                assertEquals(c.id, c.expected, CidLib.computeFileCid(c.bytes))
            }
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
    }

    @Test
    fun proofSetCases_matchFixture_whenNativeAvailable() {
        try {
            for (c in proofSetCases()) {
                val result = CidLib.computeProofSetCid(c.entries)
                assertEquals(c.id, c.expectedRoot, result.rootCid)
                c.expectedFiles.forEach { (name, expectedCid) ->
                    assertEquals("${c.id}:$name", expectedCid, result.files[name])
                }
                c.expectedTsizes.forEach { (name, expectedTsize) ->
                    assertEquals("${c.id}:tsize:$name", expectedTsize, result.tsizes[name])
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
    }

    @Test
    fun proofSetCases_f2aFromLeaves_matchesF2BytePath_whenNativeAvailable() {
        try {
            for (c in proofSetCases()) {
                val byteResult = CidLib.computeProofSetCid(c.entries)
                val leafEntries = byteResult.files.map { (name, leafCid) ->
                    NamedLeafCid(
                        name = name,
                        leafCid = leafCid,
                        tsize = byteResult.tsizes[name]
                            ?: error("missing tsize for $name in ${c.id}"),
                    )
                }
                val leafResult = CidLib.computeProofSetCidFromLeaves(leafEntries)
                assertEquals("${c.id}:root", c.expectedRoot, leafResult.rootCid)
                assertEquals("${c.id}:root-eq-byte", byteResult.rootCid, leafResult.rootCid)
                assertEquals("${c.id}:files", byteResult.files, leafResult.files)
            }
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
    }

    @Test
    fun proofsetWithInjectedMedia_matchesFixture_whenNativeAvailable() {
        try {
            val c = proofSetCases().first { it.id == "proofset_with_injected_media" }
            val result = CidLib.computeProofSetCid(c.entries)
            assertEquals(c.expectedRoot, result.rootCid)
            c.expectedFiles.forEach { (name, expectedCid) ->
                assertEquals(name, expectedCid, result.files[name])
            }
            c.expectedTsizes.forEach { (name, expectedTsize) ->
                assertEquals(name, expectedTsize, result.tsizes[name])
            }
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
    }

    @Test
    fun proofsetInjectedMedia_F2a_matchesF2RootCid_whenNativeAvailable() {
        try {
            val c = proofSetCases().first { it.id == "proofset_with_injected_media" }
            val byteResult = CidLib.computeProofSetCid(c.entries)
            val leafEntries = byteResult.files.map { (name, leafCid) ->
                NamedLeafCid(
                    name = name,
                    leafCid = leafCid,
                    tsize = byteResult.tsizes[name]
                        ?: error("missing tsize for $name"),
                )
            }
            val leafResult = CidLib.computeProofSetCidFromLeaves(leafEntries)
            assertEquals(byteResult.rootCid, leafResult.rootCid)
            assertEquals(c.expectedRoot, leafResult.rootCid)
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
    }
}
