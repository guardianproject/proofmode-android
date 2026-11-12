package org.witness.proofmode.plugins.lp.config

/**
 * A null [easScanAddressUrl] means the chain has no EAS Scan site; callers hide the link instead
 * of building one that leads nowhere.
 */
data class ChainConfig(
    val displayName: String,
    val caip2Id: String,
    val explorerAddressUrl: String,
    val easScanAddressUrl: String?,
    val rpcUrls: List<String>,
)

val SUPPORTED_CHAINS: List<ChainConfig> = listOf(
    ChainConfig(
        displayName = "Ethereum Mainnet",
        caip2Id = "eip155:1",
        explorerAddressUrl = "https://etherscan.io/address/{address}",
        easScanAddressUrl = "https://easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://eth.api.pocket.network",
            "https://ethereum-rpc.publicnode.com",
        ),
    ),
    ChainConfig(
        displayName = "Arbitrum One",
        caip2Id = "eip155:42161",
        explorerAddressUrl = "https://arbiscan.io/address/{address}",
        easScanAddressUrl = "https://arbitrum.easscan.org/address/{address}",
        rpcUrls = listOf("https://arb-one.api.pocket.network"),
    ),
    ChainConfig(
        displayName = "Base",
        caip2Id = "eip155:8453",
        explorerAddressUrl = "https://basescan.org/address/{address}",
        easScanAddressUrl = "https://base.easscan.org/address/{address}",
        rpcUrls = listOf("https://base.api.pocket.network"),
    ),
    ChainConfig(
        displayName = "Optimism",
        caip2Id = "eip155:10",
        explorerAddressUrl = "https://optimistic.etherscan.io/address/{address}",
        easScanAddressUrl = "https://optimism.easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://op.api.pocket.network",
            "https://optimism-rpc.publicnode.com",
        ),
    ),
    ChainConfig(
        displayName = "Scroll",
        caip2Id = "eip155:534352",
        explorerAddressUrl = "https://scrollscan.com/address/{address}",
        easScanAddressUrl = "https://scroll.easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://scroll.api.pocket.network",
            "https://scroll-rpc.publicnode.com",
        ),
    ),
    ChainConfig(
        displayName = "Ink",
        caip2Id = "eip155:57073",
        explorerAddressUrl = "https://explorer.inkonchain.com/address/{address}",
        easScanAddressUrl = "https://ink.easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://ink.api.pocket.network",
            "https://rpc-gel.inkonchain.com",
        ),
    ),
    ChainConfig(
        displayName = "Celo",
        caip2Id = "eip155:42220",
        explorerAddressUrl = "https://celoscan.io/address/{address}",
        easScanAddressUrl = null,
        rpcUrls = listOf(
            "https://rpc.ankr.com/celo",
            "https://celo-json-rpc.stakely.io",
        ),
    ),
    ChainConfig(
        displayName = "Sepolia Testnet",
        caip2Id = "eip155:11155111",
        explorerAddressUrl = "https://sepolia.etherscan.io/address/{address}",
        easScanAddressUrl = "https://sepolia.easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://eth-sepolia-testnet.api.pocket.network",
            "https://ethereum-sepolia-rpc.publicnode.com",
            "https://1rpc.io/sepolia",
            "https://rpc.sepolia.org",
        ),
    ),
    ChainConfig(
        displayName = "Arbitrum Sepolia",
        caip2Id = "eip155:421614",
        explorerAddressUrl = "https://sepolia.arbiscan.io/address/{address}",
        easScanAddressUrl = "https://arbitrum-sepolia.easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://arb-sepolia-testnet.api.pocket.network",
            "https://arbitrum-sepolia-rpc.publicnode.com",
        ),
    ),
    ChainConfig(
        displayName = "Optimism Sepolia",
        caip2Id = "eip155:11155420",
        explorerAddressUrl = "https://sepolia-optimism.etherscan.io/address/{address}",
        easScanAddressUrl = "https://optimism-sepolia.easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://op-sepolia-testnet.api.pocket.network",
            "https://optimism-sepolia-rpc.publicnode.com",
        ),
    ),
    ChainConfig(
        displayName = "Base Sepolia",
        caip2Id = "eip155:84532",
        explorerAddressUrl = "https://sepolia.basescan.org/address/{address}",
        easScanAddressUrl = "https://base-sepolia.easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://base-sepolia-testnet.api.pocket.network",
            "https://base-sepolia-rpc.publicnode.com",
        ),
    ),
    ChainConfig(
        displayName = "Ink Sepolia",
        caip2Id = "eip155:763373",
        explorerAddressUrl = "https://explorer-sepolia.inkonchain.com/address/{address}",
        easScanAddressUrl = "https://ink-sepolia.easscan.org/address/{address}",
        rpcUrls = listOf(
            "https://rpc-gel-sepolia.inkonchain.com",
            "https://rpc-qnd-sepolia.inkonchain.com",
        ),
    ),
    ChainConfig(
        displayName = "Celo Sepolia",
        caip2Id = "eip155:11142220",
        explorerAddressUrl = "https://sepolia.celoscan.io/address/{address}",
        easScanAddressUrl = null,
        rpcUrls = listOf(
            "https://rpc.ankr.com/celo_sepolia",
            "https://celo-sepolia.drpc.org",
        ),
    ),
)

fun ChainConfig.explorerUrl(address: String): String =
    explorerAddressUrl.replace("{address}", address)

fun ChainConfig.easScanUrl(address: String): String? =
    easScanAddressUrl?.replace("{address}", address)

fun ChainConfig.easScanAttestationUrl(attestationUid: String): String? {
    val base = easScanAddressUrl?.substringBefore("/address/{address}") ?: return null
    return "$base/attestation/view/$attestationUid"
}

fun ChainConfig.explorerTransactionUrl(txHash: String): String =
    "${explorerAddressUrl.substringBefore("/address/{address}")}/tx/$txHash"
