package org.witness.proofmode.plugins.wallet.infra.config

object WalletChainPolicy {
    const val DEFAULT_CHAIN_ID: String = "eip155:11155111"

    /**
     * Session restore / pref-notify / deep-link chain policy.
     *
     * With [sponsorshipOn] and a known, non-empty [sponsoredChains], a candidate outside the set
     * becomes the default selection: [DEFAULT_CHAIN_ID] when sponsored, else the first sponsored
     * chain. The candidate is kept when sponsorship is off, when the set is not yet known (null: no
     * probe has answered for the project), or when it is known but empty (no remap target exists).
     */
    fun resolveSessionChainId(
        storedChainId: String?,
        sponsorshipOn: Boolean,
        sponsoredChains: List<String>?,
        defaultChainId: String = DEFAULT_CHAIN_ID,
    ): String {
        val candidate = storedChainId ?: defaultChainId
        if (!sponsorshipOn || sponsoredChains.isNullOrEmpty() || candidate in sponsoredChains) {
            return candidate
        }
        val target = if (DEFAULT_CHAIN_ID in sponsoredChains) DEFAULT_CHAIN_ID else sponsoredChains.first()
        return target
    }
}
