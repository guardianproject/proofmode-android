package org.witness.proofmode.cid

import kotlinx.serialization.Serializable

@Serializable
data class ProofSetCidResult(
    val rootCid: String,
    val files: Map<String, String>,
    val tsizes: Map<String, Long>,
)
