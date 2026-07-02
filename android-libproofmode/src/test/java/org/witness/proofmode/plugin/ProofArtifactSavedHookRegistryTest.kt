package org.witness.proofmode.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ProofArtifactSavedHookRegistryTest {
    @After
    fun tearDown() {
        ProofArtifactSavedHookRegistry.clearForTests()
    }

    @Test
    fun notify_withNoRegisteredHooks_doesNotThrow() {
        ProofArtifactSavedHookRegistry.notify("hash", "hash.ots")
    }

    @Test
    fun notify_deliversToRegisteredHook() {
        var hash: String? = null
        var identifier: String? = null
        val hook = ProofArtifactSavedHook { h, id ->
            hash = h
            identifier = id
        }
        ProofArtifactSavedHookRegistry.register(hook)
        ProofArtifactSavedHookRegistry.notify("abc123", "abc123.ots")
        assertEquals("abc123", hash)
        assertEquals("abc123.ots", identifier)
    }

    @Test
    fun unregister_removesHook() {
        var count = 0
        val hook = ProofArtifactSavedHook { _, _ -> count++ }
        ProofArtifactSavedHookRegistry.register(hook)
        ProofArtifactSavedHookRegistry.notify("h", "h.ots")
        ProofArtifactSavedHookRegistry.unregister(hook)
        ProofArtifactSavedHookRegistry.notify("h", "h.ots")
        assertEquals(1, count)
    }
}
