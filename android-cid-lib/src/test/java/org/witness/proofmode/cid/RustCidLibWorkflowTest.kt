package org.witness.proofmode.cid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RustCidLibWorkflowTest {
    private val workflow = File("../.github/workflows/rust-cid-lib.yml").readText()

    @Test
    fun workflow_triggersOnCidLibPaths() {
        assertTrue(workflow.contains("android-cid-lib/rust-cid-lib/**"))
        assertTrue(workflow.contains("org/witness/proofmode/cid/uniffi/**"))
        assertTrue(workflow.contains("rust-cid-lib-uniffi-hashes.txt"))
        assertTrue(workflow.contains("android-cid-lib/build.gradle.kts"))
        assertFalse(workflow.contains("android-libproofmode/rust-cid-lib/**"))
    }

    @Test
    fun workflow_verifiesUniffiHashManifest() {
        assertTrue(workflow.contains("rust-cid-lib-uniffi-hashes.txt"))
        assertTrue(workflow.contains(":android-cid-lib:verifyUniffiBindings"))
    }

    @Test
    fun workflow_doesNotMentionJniSymbolKeeps() {
        assertFalse(workflow.contains("Java_org_witness"))
    }
}
