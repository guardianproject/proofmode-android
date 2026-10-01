package org.witness.proofmode.plugins.lp.autocapture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class LpBadgePhase { OFFCHAIN, ONCHAIN }

enum class LpRunState { IDLE, RUNNING, SUCCEEDED, SKIPPED, FAILED }

data class AutoCaptureLpItemState(
    val offchain: LpRunState = LpRunState.IDLE,
    val onchain: LpRunState = LpRunState.IDLE,
)

data class AutoCaptureLpBadgeSnapshot(
    val state: AutoCaptureLpItemState = AutoCaptureLpItemState(),
    val revision: Long = 0L,
)

object AutoCaptureLpStateRegistry {

    private val badgeSnapshots = MutableStateFlow<Map<String, AutoCaptureLpBadgeSnapshot>>(emptyMap())
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    fun getState(mediaHash: String): AutoCaptureLpItemState =
        badgeSnapshots.value[mediaHash]?.state ?: AutoCaptureLpItemState()

    fun currentBadgeSnapshot(mediaHash: String): AutoCaptureLpBadgeSnapshot =
        badgeSnapshots.value[mediaHash] ?: AutoCaptureLpBadgeSnapshot()

    fun badgeSnapshotFor(mediaHash: String): Flow<AutoCaptureLpBadgeSnapshot> =
        badgeSnapshots.map { it[mediaHash] ?: AutoCaptureLpBadgeSnapshot() }.distinctUntilChanged()

    fun updateLeg(mediaHash: String, phase: LpBadgePhase, runState: LpRunState) {
        badgeSnapshots.update { snapshots ->
            val current = snapshots[mediaHash] ?: AutoCaptureLpBadgeSnapshot()
            val state = when (phase) {
                LpBadgePhase.OFFCHAIN -> current.state.copy(offchain = runState)
                LpBadgePhase.ONCHAIN -> current.state.copy(onchain = runState)
            }
            snapshots + (mediaHash to current.copy(state = state, revision = current.revision + 1))
        }
        _updates.tryEmit(mediaHash)
    }

    fun notifyArtifactUpdated(mediaHash: String) {
        badgeSnapshots.update { snapshots ->
            val current = snapshots[mediaHash] ?: AutoCaptureLpBadgeSnapshot()
            snapshots + (mediaHash to current.copy(revision = current.revision + 1))
        }
        _updates.tryEmit(mediaHash)
    }

    /** Test-only reset; not for production use. */
    fun clearForTests() {
        badgeSnapshots.value = emptyMap()
    }
}
