package org.witness.proofmode.plugins.lp.wallet

import org.witness.proofmode.plugins.wallet.infra.abbreviateAddress

/**
 * LP-owned snapshot of wallet state for share/attestation diagnostics.
 * Intentionally free of wallet-infra types so it can cross the app boundary.
 */
data class WalletDiagnostics(
    val address: String?,
    val connected: Boolean,
) {
    /** Abbreviated wallet address for logs (e.g. 0x1234…abcd). */
    fun abbreviatedAddress(): String? {
        return address?.let(::abbreviateAddress)
    }
}
