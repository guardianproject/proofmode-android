package org.witness.proofmode.plugins.lp.attestation

/** What one receipt fetch of a pending record found, as the onchain legs act on it. */
sealed class PendingOnchainRecheckOutcome {
    /** The confirmed record is written and the pending record deleted. */
    data class Confirmed(val result: LocationProtocolAttestationResult) : PendingOnchainRecheckOutcome()

    /** Still unresolved; the pending record stays. */
    object StillPending : PendingOnchainRecheckOutcome()

    /**
     * No pending record stands in the way of a fresh submit: there was none, or its transaction
     * reverted or is unknown to the chain and the record is deleted.
     */
    object Resubmit : PendingOnchainRecheckOutcome()
}
