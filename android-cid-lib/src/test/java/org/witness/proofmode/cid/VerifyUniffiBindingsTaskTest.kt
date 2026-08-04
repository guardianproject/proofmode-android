package org.witness.proofmode.cid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VerifyUniffiBindingsTaskTest {
    @Test
    fun buildGradle_declaresVerifyUniffiBindingsTask() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("verifyUniffiBindings"))
        assertTrue(gradle.contains("rust-cid-lib-uniffi-hashes.txt"))
    }
}
