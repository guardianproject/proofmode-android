package org.witness.proofmode.plugins.lp.autocapture

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

enum class LpBadgePhase { OFFCHAIN, ONCHAIN }

enum class LpRunState { IDLE, RUNNING, SUCCEEDED, SKIPPED, FAILED }

data class AutoCaptureLpItemState(
    val offchain: LpRunState = LpRunState.IDLE,
    val onchain: LpRunState = LpRunState.IDLE,
)

object AutoCaptureLpStateRegistry {

    private val states = ConcurrentHashMap<String, AutoCaptureLpItemState>()
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    fun getState(mediaHash: String): AutoCaptureLpItemState =
        states[mediaHash] ?: AutoCaptureLpItemState()

    fun updateLeg(mediaHash: String, phase: LpBadgePhase, runState: LpRunState) {
        states.compute(mediaHash) { _, current ->
            val base = current ?: AutoCaptureLpItemState()
            when (phase) {
                LpBadgePhase.OFFCHAIN -> base.copy(offchain = runState)
                LpBadgePhase.ONCHAIN -> base.copy(onchain = runState)
            }
        }
        _updates.tryEmit(mediaHash)
    }

    fun notifyArtifactUpdated(mediaHash: String) {
        _updates.tryEmit(mediaHash)
    }

    /** Test-only reset; not for production use. */
    fun clearForTests() {
        states.clear()
    }
}
