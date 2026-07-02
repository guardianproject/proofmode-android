package org.witness.proofmode.cid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CidLibProguardTest {
    @Test
    fun proguardRules_keepCidLibFacadeAndUniffiGlue() {
        val rules = File("proguard-rules.pro").readText()
        assertTrue(rules.contains("org.witness.proofmode.cid.CidLib"))
        assertTrue(rules.contains("public static <methods>"))
        assertTrue(rules.contains("org.witness.proofmode.cid.uniffi.**"))
        assertTrue(rules.contains("static <methods>"))
    }

    @Test
    fun proguardRules_doNotKeepJniNativeMethods() {
        val rules = File("proguard-rules.pro").readText()
        val cidLibSection = rules.substringAfter("org.witness.proofmode.cid.CidLib {")
            .substringBefore("}")
        assertFalse(cidLibSection.contains("native <methods>"))
        assertFalse(rules.contains("Java_org_witness_proofmode_cid_CidLib"))
        assertFalse(rules.contains("-keepclasseswithmembernames class org.witness.proofmode.cid.CidLib"))
    }
}
