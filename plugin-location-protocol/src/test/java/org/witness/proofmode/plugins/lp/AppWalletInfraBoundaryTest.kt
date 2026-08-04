package org.witness.proofmode.plugins.lp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppWalletInfraBoundaryTest {

    @Test
    fun appModuleHasNoWalletInfraCoupling() {
        // rootDir = .../plugin-location-protocol ; repo root is its parent.
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        val appDir = File(repoRoot, "app")
        assertTrue("app module not found at ${appDir.absolutePath}", appDir.isDirectory)

        val offenders = appDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            .filter { it.readText().contains("plugins.wallet.infra") }
            .map { it.relativeTo(repoRoot).path }
            .toList()

        assertTrue(
            "app must not couple to plugin-wallet-infra; offenders: $offenders",
            offenders.isEmpty(),
        )
    }
}
