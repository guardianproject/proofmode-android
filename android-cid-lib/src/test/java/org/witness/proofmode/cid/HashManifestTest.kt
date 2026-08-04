package org.witness.proofmode.cid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class HashManifestTest {
    /** Matches rust-cid-lib-hashes.txt convention: repo-root paths with android-cid-lib/ prefix. */
    private val pathPrefix = "android-cid-lib"

    @Test
    fun uniffiHashManifest_matchesCommittedGlueFiles() {
        val manifest = File("rust-cid-lib/rust-cid-lib-uniffi-hashes.txt")
        assertTrue("UniFFI hash manifest missing", manifest.exists())

        val uniffiDir = File("src/main/java/org/witness/proofmode/cid/uniffi")
        assertTrue("UniFFI output dir missing", uniffiDir.isDirectory)

        val ktFiles = uniffiDir.listFiles { f -> f.extension == "kt" }?.sortedBy { it.name }.orEmpty()
        assertTrue("No .kt files under uniffi/", ktFiles.isNotEmpty())

        val manifestLines = manifest.readLines().filter { it.isNotBlank() }.sorted()
        val computedLines = ktFiles.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            "$hash  $pathPrefix/src/main/java/org/witness/proofmode/cid/uniffi/${file.name}"
        }.sorted()

        assertTrue(
            "UniFFI hash manifest drift — regenerate rust-cid-lib-uniffi-hashes.txt",
            manifestLines == computedLines,
        )
    }

    @Test
    fun soHashManifest_hasFourAbiEntries() {
        val manifest = File("rust-cid-lib/rust-cid-lib-hashes.txt")
        assertTrue(manifest.exists())
        val lines = manifest.readLines().filter { it.isNotBlank() }
        assertTrue("Expected 4 ABI .so entries", lines.size == 4)
        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
            assertTrue(lines.any { it.contains("/jniLibs/$abi/librust_cid_lib.so") })
        }
    }
}
