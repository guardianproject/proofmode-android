package org.witness.proofmode.plugins.wallet.infra

import org.junit.Assert.assertFalse
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.api.WalletCapabilityProvider
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector

class WalletCapabilityProviderTest {

    @Test
    fun privyConnector_reportsSponsorshipInactive() {
        val connector = PrivyWalletConnector(WalletSdkConfig("id", "client"))
        assertFalse((connector as WalletCapabilityProvider).isSponsorshipActive)
    }
}
