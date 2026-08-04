package org.witness.proofmode.plugins.lp.deeplink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.witness.proofmode.plugins.lp.deeplink.ParamOutcome
import org.witness.proofmode.plugins.lp.R
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

        val skipped = mutableListOf<String>()
        var appliedChain: String? = null
        var appliedSponsor: Boolean? = null
        var appliedProjectId: String? = null

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
                    appliedChain = chainId
                    syncChain(chainId, providerSelection)
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
                    appliedSponsor = enabled
                    Timber.tag(TAG).i("Applied sponsor=%s", enabled)
                }
                ParamOutcome.INVALID -> skipped.add("sponsor:invalid_value")
                ParamOutcome.ABSENT -> Unit
            }
        }

        parseResult.projectId?.let { param ->
            when (param.outcome) {
                ParamOutcome.VALID -> {
                    val projectId = requireNotNull(param.value)
                    sessionStore.saveZeroDevProjectIdOverride(projectId)
                    appliedProjectId = projectId
                    Timber.tag(TAG).i("Applied projectId override (len=%d)", projectId.length)
                }
                ParamOutcome.INVALID -> skipped.add("projectId:invalid_value")
                ParamOutcome.ABSENT -> Unit
            }
        }

        return WalletDeepLinkResult(
            appliedChain = appliedChain,
            appliedSponsor = appliedSponsor,
            appliedProjectId = appliedProjectId,
            skipped = skipped,
            userMessage = buildSummaryMessage(context, appliedChain, appliedSponsor, appliedProjectId, skipped),
        )
    }

    private suspend fun syncChain(
        chainId: String,
        providerSelection: WalletProviderSelection,
    ) {
        withContext(Dispatchers.IO) {
            val activeConnector = providerSelection.activeConnector
            if (activeConnector.getIdentity() != null) {
                activeConnector.setChain(chainId)
            } else {
                WalletProviderFactory.privyConnector(providerSelection).setChain(chainId)
            }
        }
    }

    private fun buildSummaryMessage(
        context: Context,
        chain: String?,
        sponsor: Boolean?,
        projectId: String?,
        skipped: List<String>,
    ): String? {
        if (chain == null && sponsor == null && projectId == null && skipped.isEmpty()) return null
        if (skipped.isNotEmpty() && (chain != null || sponsor != null || projectId != null)) {
            return context.getString(R.string.wallet_deep_link_partial_apply)
        }
        if (chain != null || sponsor != null || projectId != null) {
            return context.getString(R.string.wallet_deep_link_applied_summary)
        }
        return null
    }
}
