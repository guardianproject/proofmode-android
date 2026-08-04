package org.witness.proofmode.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibproofmodeNoCidClasspathTest {
    private val forbiddenTokens = listOf(
        "CidLib",
        "NamedBytes",
        "CidOptions",
        "ProofSetCidPersister",
        "LocalIpfsCidGate",
        "IpfsCidArtifacts",
        "storage.uniffi",
        "rust_cid_lib",
        "librust_cid_lib",
    )

    @Test
    fun buildGradle_doesNotDependOnAndroidCidLib() {
        val gradle = File("build.gradle").readText()
        assertFalse(gradle.contains("implementation project(':android-cid-lib')"))
    }

    @Test
    fun mainSources_containNoForbiddenCidComputationTokens() {
        val mainJava = File("src/main/java")
        val mainRoot = File("src/main")
        val violations = mutableListOf<String>()
        for (root in listOf(mainJava, mainRoot)) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val text = file.readText()
                    forbiddenTokens.forEach { token ->
                        if (text.contains(token)) {
                            violations.add("${file.path}: $token")
                        }
                    }
                }
        }
        assertTrue("forbidden tokens in main sources: $violations", violations.isEmpty())
    }

    @Test
    fun persisterSource_deleted() {
        assertFalse(File("src/main/java/org/witness/proofmode/storage/ProofSetCidPersister.kt").exists())
    }

    @Test
    fun gateAndArtifacts_deletedFromLibproofmode() {
        assertFalse(File("src/main/java/org/witness/proofmode/storage/LocalIpfsCidGate.kt").exists())
        assertFalse(File("src/main/java/org/witness/proofmode/storage/IpfsCidArtifacts.kt").exists())
    }
}
