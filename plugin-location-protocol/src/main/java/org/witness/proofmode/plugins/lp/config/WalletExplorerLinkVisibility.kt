package org.witness.proofmode.plugins.lp.config

internal data class WalletExplorerLinkVisibility(val transactions: Boolean, val attestations: Boolean)

/** An unresolved chain hides both links: a click on either would do nothing. */
internal fun walletExplorerLinkVisibility(chain: ChainConfig?): WalletExplorerLinkVisibility =
    WalletExplorerLinkVisibility(
        transactions = chain != null,
        attestations = chain?.easScanAddressUrl != null,
    )
