package org.witness.proofmode.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaWatcherProofWriteHookWiringTest {
    @Test
    fun writeProof_tailInvokesProofWriteHookRegistry() {
        val src = File("src/main/java/org/witness/proofmode/service/MediaWatcher.kt").readText()
        assertTrue(src.contains("ProofWriteHookRegistry.notify"))
        assertTrue(src.contains("ProofWriteEvent("))
        assertFalse(src.contains("ProofSetCidPersister"))
        assertFalse(src.contains("CidLib"))
        assertFalse(src.contains("org.witness.proofmode.storage.ProofSetCidPersister"))
    }
}
