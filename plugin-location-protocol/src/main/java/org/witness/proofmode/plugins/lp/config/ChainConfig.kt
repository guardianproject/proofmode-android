package org.witness.proofmode.plugins.lp.config

data class ChainConfig(
    val displayName: String,
    val caip2Id: String,
    val explorerAddressUrl: String,
    val easScanAddressUrl: String,
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
)

fun ChainConfig.explorerUrl(address: String): String =
    explorerAddressUrl.replace("{address}", address)

fun ChainConfig.easScanUrl(address: String): String =
    easScanAddressUrl.replace("{address}", address)

fun ChainConfig.easScanAttestationUrl(attestationUid: String): String {
    val base = easScanAddressUrl.substringBefore("/address/{address}")
    return "$base/attestation/view/$attestationUid"
}
