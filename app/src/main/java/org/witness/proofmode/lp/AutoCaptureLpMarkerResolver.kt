package org.witness.proofmode.lp

import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpItemState
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.plugins.lp.autocapture.LpRunState
import org.witness.proofmode.storage.StorageProvider

enum class LpOffchainBadge { NONE, SPINNER, FINAL }

enum class LpOnchainBadge { NONE, SPINNER, PENDING, CONFIRMED }

data class LpBadgeUiState(
    val offchain: LpOffchainBadge = LpOffchainBadge.NONE,
    val onchain: LpOnchainBadge = LpOnchainBadge.NONE,
)

object AutoCaptureLpMarkerResolver {

    fun resolve(
        mediaHash: String,
        registryState: AutoCaptureLpItemState,
        artifactStore: LocationProtocolArtifactStore,
        storageProvider: StorageProvider,
    ): LpBadgeUiState {
        val offchain = resolveOffchain(mediaHash, registryState, artifactStore, storageProvider)
        val onchain = resolveOnchain(mediaHash, registryState, artifactStore, storageProvider)
        return LpBadgeUiState(offchain = offchain, onchain = onchain)
    }

    private fun resolveOffchain(
        mediaHash: String,
        registryState: AutoCaptureLpItemState,
        artifactStore: LocationProtocolArtifactStore,
        storageProvider: StorageProvider,
    ): LpOffchainBadge {
        val offchainId = "$mediaHash${LocationProtocolArtifactStore.OFFCHAIN_SUFFIX}"
        val legacyId = "$mediaHash${LocationProtocolArtifactStore.LEGACY_SUFFIX}"
        if (storageProvider.proofIdentifierExists(mediaHash, offchainId) ||
            storageProvider.proofIdentifierExists(mediaHash, legacyId) ||
            artifactStore.readOffchainAttestation(mediaHash) != null
        ) {
            return LpOffchainBadge.FINAL
        }
        if (registryState.offchain == LpRunState.RUNNING) {
            return LpOffchainBadge.SPINNER
        }
        return LpOffchainBadge.NONE
    }

    private fun resolveOnchain(
        mediaHash: String,
        registryState: AutoCaptureLpItemState,
        artifactStore: LocationProtocolArtifactStore,
        storageProvider: StorageProvider,
    ): LpOnchainBadge {
        val onchainId = "$mediaHash${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}"
        val pendingId = "$mediaHash${LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX}"
        if (storageProvider.proofIdentifierExists(mediaHash, onchainId) ||
            artifactStore.readOnchainAttestation(mediaHash) != null
        ) {
            return LpOnchainBadge.CONFIRMED
        }
        if (storageProvider.proofIdentifierExists(mediaHash, pendingId) ||
            artifactStore.readPendingOnchainAttestation(mediaHash) != null
        ) {
            return LpOnchainBadge.PENDING
        }
        if (registryState.onchain == LpRunState.RUNNING) {
            return LpOnchainBadge.SPINNER
        }
        return LpOnchainBadge.NONE
    }
}
