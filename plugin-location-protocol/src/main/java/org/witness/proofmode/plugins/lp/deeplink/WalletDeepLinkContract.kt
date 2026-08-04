package org.witness.proofmode.plugins.lp.deeplink

import android.content.Intent
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkResult

object WalletDeepLinkContract {
    const val SCHEME = "proofmode"
    const val HOST_WALLET = "wallet"

    const val EXTRA_DEEP_LINK_REJECTED =
        "org.witness.proofmode.plugins.lp.EXTRA_DEEP_LINK_REJECTED"
    const val EXTRA_DEEP_LINK_MESSAGE =
        "org.witness.proofmode.plugins.lp.EXTRA_DEEP_LINK_MESSAGE"
    const val EXTRA_DEEP_LINK_APPLIED_CHAIN =
        "org.witness.proofmode.plugins.lp.EXTRA_DEEP_LINK_APPLIED_CHAIN"
}

fun Intent.putWalletDeepLinkResult(result: WalletDeepLinkResult): Intent = apply {
    putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, result.rejected)
    putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE, result.userMessage)
    putExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_APPLIED_CHAIN, result.appliedChain)
}
