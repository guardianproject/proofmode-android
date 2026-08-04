package org.witness.proofmode.plugins.lp.attestation

import kotlinx.serialization.Serializable

/**
 * Result produced by [EASAttestationManager.createOffchainLocationAttestation].
 *
 * Mapping from legacy `org.witness.proofmode.LocationAttestationResult` (storacha-integration):
 * ```
 * Legacy field           -> This field
 * attestationUID         -> uid
 * (no direct equivalent) -> schemaId
 * (no direct equivalent) -> attesterAddress
 * timestamp              -> timestamp
 * (no direct equivalent) -> offchainPayloadJson  (the full EAS typed-data JSON)
 * (no direct equivalent) -> artifactPath         (resolved file path / URI of the persisted .lp.json)
 * success / error        -> encoded in the Result<> wrapper, not this data class
 * transactionHash        -> not included (Phase 2 is offchain-only; no tx hash)
 * gasUsed                -> not included (offchain-only)
 * latitude / longitude   -> embedded in offchainPayloadJson, not top-level here
 * type ("onchain"|"offchain") -> implied offchain for Phase 2; Phase 3 can add type if needed
 * ```
 */
@Serializable
data class LocationProtocolAttestationResult(
    /** EAS attestation UID. Empty string when offchain-only (Phase 2). */
    val uid: String,

    /**
     * EAS schema ID (EIP-712 schema UID).
     * Populated from [EASAttestationManager.EAS_SCHEMA_ID]; may be empty in Phase 2.
     */
    val schemaId: String,

    /**
     * Ethereum address of the attester. Empty string until Phase 3 wires the bridge.
     */
    val attesterAddress: String,

    /** Wall-clock milliseconds at attestation creation time. */
    val timestamp: Long,

    /**
     * The full EAS typed-data JSON payload (EIP-712 `domain + types + message`).
     * This is what gets persisted as the `.lp.json` artifact.
     */
    val offchainPayloadJson: String,

    /**
     * The resolved file path / URI string where the `.lp.json` was persisted
     * by [LocationProtocolArtifactStore]. This is the storage location reported by
     * the backend, not the logical `<hash>.lp.json` identifier.
     */
    val artifactPath: String
)
