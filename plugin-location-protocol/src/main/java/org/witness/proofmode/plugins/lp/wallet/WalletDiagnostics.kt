package org.witness.proofmode.plugins.lp.wallet

/**
 * LP-owned snapshot of wallet state for share/attestation diagnostics.
 * Intentionally free of wallet-infra types so it can cross the app boundary.
 */
data class WalletDiagnostics(
    val chainId: String?,
    val address: String?,
    val connectorName: String,
    val sponsorshipActive: Boolean?,
    val connected: Boolean,
) {
    /** Abbreviated wallet address for logs (e.g. 0x1234…abcd). */
    fun abbreviatedAddress(): String? {
        val a = address ?: return null
        if (a.length <= 12) return a
        return "${a.take(6)}…${a.takeLast(4)}"
    }
}
