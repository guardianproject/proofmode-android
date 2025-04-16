package org.witness.proofmode.camera.fragments

sealed class RecordingState {
    object Idle : RecordingState()
    object Recording : RecordingState()
    object Paused : RecordingState()
    object Stopped: RecordingState()
    data class Error(val message: String) : RecordingState()
}