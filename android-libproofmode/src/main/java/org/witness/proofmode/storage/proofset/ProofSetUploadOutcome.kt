package org.witness.proofmode.storage.proofset

/**
 * Unified Strategy return contract. Facade advances the membership stamp and emits
 * UX [org.witness.proofmode.storage.StorageListener] callbacks only from this outcome.
 */
sealed class ProofSetUploadOutcome {
    data class Success(val resultUri: String?) : ProofSetUploadOutcome()
    data class Failed(val error: Exception?) : ProofSetUploadOutcome()
}
