package org.witness.proofmode.cid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VerifyRustCidLibTaskTest {
    @Test
    fun buildGradle_declaresVerifyRustCidLibTask() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("verifyRustCidLib"))
    }
}
