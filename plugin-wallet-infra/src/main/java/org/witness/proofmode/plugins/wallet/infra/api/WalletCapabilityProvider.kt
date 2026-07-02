package org.witness.proofmode.plugins.wallet.infra.api

import org.witness.proofmode.plugins.wallet.infra.model.WalletCapabilities

interface WalletCapabilityProvider {
    fun getCapabilities(): WalletCapabilities
    val isSponsorshipActive: Boolean
}
