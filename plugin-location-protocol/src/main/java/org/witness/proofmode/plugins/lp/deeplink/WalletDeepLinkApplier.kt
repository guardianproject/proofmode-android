package org.witness.proofmode.plugins.lp.deeplink

import android.content.Context
import org.witness.proofmode.plugins.lp.deeplink.ParamOutcome
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkParseResult
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkResult
import org.witness.proofmode.plugins.wallet.infra.factory.WalletProviderFactory
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore
import org.witness.proofmode.plugins.wallet.infra.model.WalletProviderSelection
import timber.log.Timber

internal object WalletDeepLinkApplier {

    private const val TAG = "WalletDeepLink"

    suspend fun apply(
        context: Context,
        parseResult: WalletDeepLinkParseResult,
        sessionStore: WalletSessionStore,
        providerSelection: WalletProviderSelection,
    ): WalletDeepLinkResult {
        if (!parseResult.isWalletRoute) {
            return WalletDeepLinkResult(userMessage = context.getString(R.string.wallet_deep_link_no_params))
        }

        if (parseResult.chain?.outcome == ParamOutcome.INVALID) {
            Timber.tag(TAG).w("Deep link rejected: invalid chain parameter")
            return WalletDeepLinkResult(
                rejected = true,
                userMessage = context.getString(R.string.wallet_deep_link_chain_rejected),
            )
        }

        var wroteAny = false
        var skippedAny = false
        var requestedChain: String? = null

        val hasAnyQueryParam = listOfNotNull(
            parseResult.chain,
            parseResult.sponsor,
            parseResult.projectId,
        ).any { it.outcome != ParamOutcome.ABSENT }

        if (!hasAnyQueryParam) {
            return WalletDeepLinkResult(
                userMessage = context.getString(R.string.wallet_deep_link_no_params),
            )
        }

        parseResult.chain?.let { param ->
            when (param.outcome) {
                ParamOutcome.VALID -> {
                    val chainId = requireNotNull(param.value)
                    sessionStore.saveChainId(chainId)
                    requestedChain = chainId
                    wroteAny = true
                    Timber.tag(TAG).i("Applied chain=%s", chainId)
                }
                ParamOutcome.INVALID -> { /* unreachable — hard reject above */ }
                ParamOutcome.ABSENT -> Unit
            }
        }

        parseResult.sponsor?.let { param ->
            when (param.outcome) {
                ParamOutcome.VALID -> {
                    val enabled = requireNotNull(param.value)
                    sessionStore.saveSponsorTransactionsEnabled(enabled)
                    wroteAny = true
                    Timber.tag(TAG).i("Applied sponsor=%s", enabled)
                }
                ParamOutcome.INVALID -> skippedAny = true
                ParamOutcome.ABSENT -> Unit
            }
        }

        parseResult.projectId?.let { param ->
            when (param.outcome) {
                ParamOutcome.VALID -> {
                    val projectId = requireNotNull(param.value)
                    sessionStore.saveZeroDevProjectIdOverride(projectId)
                    wroteAny = true
                    Timber.tag(TAG).i("Applied projectId override (len=%d)", projectId.length)
                }
                ParamOutcome.INVALID -> skippedAny = true
                ParamOutcome.ABSENT -> Unit
            }
        }

        // Persist every param first, then sync once, so the sync resolves against the final chain,
        // sponsor flag, and project override. The sponsor and project writes also fire the prefs
        // listener, which syncs too; both read the stored chain before the connector's own, so
        // neither can push an older chain after the link's.
        WalletProviderFactory.syncSessionChain(
            selection = providerSelection,
            sessionStore = sessionStore,
            buildProjectId = WalletSigningPlugin.buildDefaultZeroDevProjectId(),
            requestedChainId = requestedChain,
        )

        return WalletDeepLinkResult(userMessage = buildSummaryMessage(context, wroteAny, skippedAny))
    }

    private fun buildSummaryMessage(
        context: Context,
        wroteAny: Boolean,
        skippedAny: Boolean,
    ): String? = when {
        !wroteAny -> null
        skippedAny -> context.getString(R.string.wallet_deep_link_partial_apply)
        else -> context.getString(R.string.wallet_deep_link_applied_summary)
    }
}
