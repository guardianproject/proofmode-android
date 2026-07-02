package org.witness.proofmode.plugins.lp.deeplink

import android.net.Uri
import org.witness.proofmode.plugins.lp.deeplink.ParamOutcome
import org.witness.proofmode.plugins.lp.deeplink.ParsedParam
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkParseResult
import org.witness.proofmode.plugins.wallet.infra.BuildConfig
import org.witness.proofmode.plugins.wallet.infra.config.UuidFormat
import java.net.URLDecoder

class WalletDeepLinkParser(
    private val sponsorshipEnabled: Boolean = BuildConfig.FEATURE_SPONSORSHIP_ENABLED,
    private val supportedChainIds: Set<String> = SUPPORTED_CHAINS.map { it.caip2Id }.toSet(),
) {
    fun parse(uri: Uri): WalletDeepLinkParseResult {
        val host = uri.host
        val isWalletRoute =
            uri.scheme == WalletDeepLinkContract.SCHEME &&
                host == WalletDeepLinkContract.HOST_WALLET
        if (!isWalletRoute) {
            return WalletDeepLinkParseResult(host = host, isWalletRoute = false)
        }

        val chain = parseChain(uri)
        val sponsor = parseSponsor(uri)
        val projectId = parseProjectId(uri)

        return WalletDeepLinkParseResult(
            chain = chain,
            sponsor = sponsor,
            projectId = projectId,
            host = host,
            isWalletRoute = true,
        )
    }

    private fun parseChain(uri: Uri): ParsedParam<String> {
        if (!uri.queryParameterNames.contains("chain")) {
            return ParsedParam(ParamOutcome.ABSENT)
        }
        val raw = uri.getQueryParameter("chain")
        if (raw.isNullOrBlank()) {
            return ParsedParam(ParamOutcome.INVALID)
        }
        val decoded = URLDecoder.decode(raw.trim(), Charsets.UTF_8.name())
        return if (supportedChainIds.contains(decoded)) {
            ParsedParam(ParamOutcome.VALID, decoded)
        } else {
            ParsedParam(ParamOutcome.INVALID)
        }
    }

    private fun parseSponsor(uri: Uri): ParsedParam<Boolean> {
        if (!sponsorshipEnabled) return gatedBooleanParam()
        if (!uri.queryParameterNames.contains("sponsor")) {
            return ParsedParam(ParamOutcome.ABSENT)
        }
        val raw = uri.getQueryParameter("sponsor")?.trim() ?: return ParsedParam(ParamOutcome.INVALID)
        return when (raw.lowercase()) {
            "true" -> ParsedParam(ParamOutcome.VALID, true)
            "false" -> ParsedParam(ParamOutcome.VALID, false)
            else -> ParsedParam(ParamOutcome.INVALID)
        }
    }

    private fun parseProjectId(uri: Uri): ParsedParam<String> {
        if (!sponsorshipEnabled) return gatedStringParam()
        if (!uri.queryParameterNames.contains("projectId")) {
            return ParsedParam(ParamOutcome.ABSENT)
        }
        val raw = uri.getQueryParameter("projectId")?.trim()
            ?: return ParsedParam(ParamOutcome.INVALID)
        return if (UuidFormat.isValid(raw)) {
            ParsedParam(ParamOutcome.VALID, raw)
        } else {
            ParsedParam(ParamOutcome.INVALID)
        }
    }

    /** Sponsorship compile gate: keys are ignored when disabled (always ABSENT). */
    private fun gatedBooleanParam(): ParsedParam<Boolean> = ParsedParam(ParamOutcome.ABSENT)

    private fun gatedStringParam(): ParsedParam<String> = ParsedParam(ParamOutcome.ABSENT)
}
