package org.witness.proofmode.plugins.ipfscid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginIpfsCidModuleTest {
    @Test
    fun moduleBuildFile_exists() {
        assertTrue(File("build.gradle.kts").exists())
    }

    @Test
    fun settingsGradle_includesPluginIpfsCid() {
        val settings = File("../settings.gradle").readText()
        assertTrue(settings.contains(":plugin-ipfs-cid"))
    }
}
