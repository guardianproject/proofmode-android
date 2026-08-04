package org.witness.proofmode.cid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UniffiGluePackageTest {
    @Test
    fun uniffiGlue_livesUnderCidPackage() {
        val dir = File("src/main/java/org/witness/proofmode/cid/uniffi")
        assertTrue(dir.isDirectory)
        val ktFiles = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(ktFiles.isNotEmpty())
        val text = ktFiles.first().readText()
        assertTrue(text.contains("org.witness.proofmode.cid.uniffi"))
    }
}
