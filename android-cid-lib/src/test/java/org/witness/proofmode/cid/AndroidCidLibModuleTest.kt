package org.witness.proofmode.cid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidCidLibModuleTest {
    @Test
    fun settingsGradle_includesAndroidCidLib() {
        val settings = File("../settings.gradle").readText()
        assertTrue(settings.contains(":android-cid-lib"))
    }

    @Test
    fun buildGradle_declaresCidNamespace() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("org.witness.proofmode.cid"))
    }
}
