package org.witness.proofmode.plugins.wallet.infra.config

data class SessionChainResolution(
    val chainId: String,
    val persistRemap: Boolean,
)

object WalletChainPolicy {
    const val DEFAULT_CHAIN_ID: String = "eip155:11155111"

    val LEFTOVER_MAINNET_CHAIN_IDS: Set<String> = setOf(
        "eip155:1",
        "eip155:42161",
        "eip155:8453",
    )

    val SPONSORED_VISIBLE_CHAIN_IDS: List<String> = listOf(
        "eip155:11155111",
        "eip155:421614",
    )

    fun isLeftoverMainnet(chainId: String): Boolean =
        chainId in LEFTOVER_MAINNET_CHAIN_IDS

    /**
     * Session restore / pref-notify chain policy.
     *
     * When [sponsorshipOn] and the candidate is a leftover mainnet CAIP-2 id, result is Sepolia.
     * [persistRemap] is true only when a **stored** leftover id is rewritten (empty store does
     * not persist). Do not remap when sponsorship is off.
     */
    fun resolveSessionChainId(
        storedChainId: String?,
        sponsorshipOn: Boolean,
        defaultChainId: String = DEFAULT_CHAIN_ID,
    ): SessionChainResolution {
        val candidate = storedChainId ?: defaultChainId
        if (sponsorshipOn && isLeftoverMainnet(candidate)) {
            return SessionChainResolution(
                chainId = DEFAULT_CHAIN_ID,
                persistRemap = storedChainId != null && storedChainId != DEFAULT_CHAIN_ID,
            )
        }
        return SessionChainResolution(chainId = candidate, persistRemap = false)
    }
}
