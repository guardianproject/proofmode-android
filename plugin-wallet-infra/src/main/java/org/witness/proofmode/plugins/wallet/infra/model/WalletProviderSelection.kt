package org.witness.proofmode.plugins.wallet.infra.model

import org.witness.proofmode.plugins.wallet.infra.api.WalletConnector
import org.witness.proofmode.plugins.wallet.infra.api.WalletSigner
import org.witness.proofmode.plugins.wallet.infra.api.WalletTransactionSender

data class WalletProviderSelection(
    val selectedProvider: WalletProviderId,
    val activeSigner: WalletSigner,
    val activeConnector: WalletConnector,
    val activeTransactionSender: WalletTransactionSender? = null,
)
