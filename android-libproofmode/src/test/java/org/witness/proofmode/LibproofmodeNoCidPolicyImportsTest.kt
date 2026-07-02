package org.witness.proofmode

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class LibproofmodeNoCidPolicyImportsTest {
    @Test
    fun mainSources_containNoCidPolicyTypes() {
        val mainRoot = File("src/main/java")
        val text = mainRoot.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(text.contains("LocalIpfsCidGate"))
        assertFalse(text.contains("ProofSetCidPersister"))
        assertFalse(text.contains("IpfsCidArtifacts"))
    }
}
