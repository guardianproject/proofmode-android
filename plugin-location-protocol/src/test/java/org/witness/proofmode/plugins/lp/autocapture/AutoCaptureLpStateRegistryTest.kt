package org.witness.proofmode.plugins.lp.autocapture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AutoCaptureLpStateRegistryTest {

    @Before
    fun reset() {
        AutoCaptureLpStateRegistry.clearForTests()
    }

    @Test
    fun updateLeg_setsPerLegState() {
        AutoCaptureLpStateRegistry.updateLeg("hash1", LpBadgePhase.OFFCHAIN, LpRunState.RUNNING)
        val state = AutoCaptureLpStateRegistry.getState("hash1")
        assertEquals(LpRunState.RUNNING, state.offchain)
        assertEquals(LpRunState.IDLE, state.onchain)
    }

    @Test(timeout = 5_000)
    fun updates_emitsMediaHashOnChange() = runBlocking {
        val deferred = async(Dispatchers.Default) {
            AutoCaptureLpStateRegistry.updates.first { it == "hash1" }
        }
        delay(100)
        AutoCaptureLpStateRegistry.updateLeg("hash1", LpBadgePhase.ONCHAIN, LpRunState.RUNNING)
        assertEquals("hash1", withTimeout(2_000) { deferred.await() })
    }

    @Test(timeout = 5_000)
    fun notifyArtifactUpdated_emitsRefresh() = runBlocking {
        val deferred = async(Dispatchers.Default) {
            AutoCaptureLpStateRegistry.updates.first { it == "hash2" }
        }
        delay(100)
        AutoCaptureLpStateRegistry.notifyArtifactUpdated("hash2")
        assertEquals("hash2", withTimeout(2_000) { deferred.await() })
    }
}
