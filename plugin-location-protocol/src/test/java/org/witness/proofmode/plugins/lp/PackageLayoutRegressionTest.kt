package org.witness.proofmode.plugins.lp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.lp.attestation.EASAttestationManager
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolAttestationCoordinator
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.config.ChainConfig
import org.witness.proofmode.plugins.lp.config.SUPPORTED_CHAINS
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkParseResult
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkResult
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin

class PackageLayoutRegressionTest {

    @Test
    fun attestationTypes_resolveFromAttestationPackage() {
        assertTrue(EASAttestationManager::class.java.name.contains(".attestation."))
        assertTrue(LocationProtocolAttestationCoordinator::class.java.name.contains(".attestation."))
    }

    @Test
    fun walletTypes_resolveFromWalletPackage() {
        assertTrue(WalletSigningPlugin::class.java.name.contains(".wallet."))
    }

    @Test
    fun configTypes_resolveFromConfigPackage() {
        assertTrue(ChainConfig::class.java.name.contains(".config."))
        assertTrue(SUPPORTED_CHAINS.isNotEmpty())
    }

    @Test
    fun autocaptureTypes_resolveFromAutocapturePackage() {
        assertTrue(AutoCaptureLpMode::class.java.name.contains(".autocapture."))
    }

    @Test
    fun deeplinkDtos_resolveFromDeeplinkPackage() {
        assertTrue(WalletDeepLinkParseResult::class.java.name.contains(".deeplink."))
        assertTrue(WalletDeepLinkResult::class.java.name.contains(".deeplink."))
    }

    @Test
    fun rootPackage_containsOnlyFacadeEntryPoint() {
        assertEquals(
            "org.witness.proofmode.plugins.lp",
            LocationProtocolPlugin::class.java.packageName,
        )
        val rootDir = locateLpSourceRoot()
        val rootKotlinSources = rootDir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertEquals(1, rootKotlinSources.size)
        assertEquals("LocationProtocolPlugin.kt", rootKotlinSources.single().name)
    }

    private fun locateLpSourceRoot(): java.io.File {
        val candidates = listOf(
            java.io.File("src/main/java/org/witness/proofmode/plugins/lp"),
            java.io.File("plugin-location-protocol/src/main/java/org/witness/proofmode/plugins/lp"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate lp source root; tried: ${candidates.map { it.path }}")
    }
}
