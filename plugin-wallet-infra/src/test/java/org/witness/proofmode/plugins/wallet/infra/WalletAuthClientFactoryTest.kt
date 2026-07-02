package org.witness.proofmode.plugins.wallet.infra

import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderId
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderSelection
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
import org.witness.proofmode.plugins.wallet.infra.zerodev.ZeroDevSmartAccountConnector

class WalletAuthClientFactoryTest {

    @Test
    fun authClient_returnsPrivyConnectorFromSelection() {
        val privy = mock<PrivyWalletConnector>()
        val zeroDev = mock<ZeroDevSmartAccountConnector>()
        whenever(zeroDev.privyConnector).thenReturn(privy)
        val selection = WalletProviderSelection(
            WalletProviderId.ZERODEV,
            zeroDev,
            zeroDev,
            zeroDev,
        )
        assertSame(privy, WalletProviderFactory.authClient(selection))
    }
}
