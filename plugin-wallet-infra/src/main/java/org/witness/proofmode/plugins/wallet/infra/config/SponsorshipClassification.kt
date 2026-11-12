package org.witness.proofmode.plugins.wallet.infra.config

/**
 * How one chain answered a sponsorship probe. [SPONSORED]: the paymaster attached itself.
 * [UNSPONSORED]: the project has no sponsoring policy there, or gas estimates came back without a
 * paymaster. [UNKNOWN]: no answer that can be trusted either way.
 */
internal enum class SponsorshipClassification {
    SPONSORED,
    UNSPONSORED,
    UNKNOWN,
}
