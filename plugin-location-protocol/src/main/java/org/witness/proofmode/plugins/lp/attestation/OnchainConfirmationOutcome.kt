package org.witness.proofmode.plugins.lp.attestation

/** How the background receipt poll for a submitted onchain attestation ended. */
internal sealed class OnchainConfirmationOutcome {
    data class Confirmed(val result: LocationProtocolAttestationResult) : OnchainConfirmationOutcome()

    /** Mined with a failed status; nothing was attested. */
    object Reverted : OnchainConfirmationOutcome()

    /** The poll errored or ran out without a receipt; the transaction may still land. */
    data class Failed(val error: Exception) : OnchainConfirmationOutcome()
}
