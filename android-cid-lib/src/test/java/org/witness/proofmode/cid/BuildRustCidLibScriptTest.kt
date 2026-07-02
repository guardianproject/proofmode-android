package org.witness.proofmode.cid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildRustCidLibScriptTest {
    @Test
    fun buildScript_outputsToCidLibModulePaths() {
        val script = File("scripts/build-rust-cid-lib.sh").readText()
        assertTrue(script.contains("cargo ndk"))
        assertTrue(script.contains("uniffi-bindgen"))
        assertTrue(script.contains("org/witness/proofmode/cid/uniffi"))
        assertTrue(script.contains("arm64-v8a/librust_cid_lib.so"))
        assertFalse(script.contains("org/witness/proofmode/storage/uniffi"))
    }

    @Test
    fun jniLibs_existForFourAbis() {
        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
            val so = File("src/main/jniLibs/$abi/librust_cid_lib.so")
            assertTrue("$abi .so missing", so.exists() && so.length() > 0)
        }
    }
}
