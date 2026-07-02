package org.witness.proofmode.plugins.wallet.infra.zerodev

import dev.zerodev.aa.Account
import dev.zerodev.aa.Context
import dev.zerodev.aa.KernelVersion
import dev.zerodev.aa.Signer
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector

data class SponsoredAccountSession(
    val account: Account,
    val context: Context,
    val signer: Signer,
)

fun interface SponsoredAccountProvisioner {
    fun provision(
        chainId: String,
        config: ZeroDevConfig,
        privyConnector: PrivyWalletConnector,
    ): SponsoredAccountSession
}

object DefaultSponsoredAccountProvisioner : SponsoredAccountProvisioner {
    override fun provision(
        chainId: String,
        config: ZeroDevConfig,
        privyConnector: PrivyWalletConnector,
    ): SponsoredAccountSession {
        val bridge = PrivyZeroDevBridge(privyConnector)
        val signer = Signer.custom(bridge)
        val numericChainId =
            chainId.removePrefix("eip155:").toLongOrNull() ?: chainId.toLong()
        val context = Context.create(
            config.projectId,
            config.bundlerUrl,
            config.paymasterUrl,
            numericChainId,
        )
        val account = context.newAccount7702(signer, KernelVersion.V3_3)
        return SponsoredAccountSession(account, context, signer)
    }
}
