package org.witness.proofmode

sealed class LpActivationState {
    data object Idle : LpActivationState()
    data object Activating : LpActivationState()
    data object Ready : LpActivationState()
    data object Failed : LpActivationState()
}
