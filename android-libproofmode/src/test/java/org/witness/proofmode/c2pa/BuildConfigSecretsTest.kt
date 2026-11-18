package org.witness.proofmode.c2pa

import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.library.BuildConfig

/**
 * The Trufo TSA key now lives server-side in ProofSign. The secrets plugin turns every key it
 * finds into a BuildConfig field, so a stale local secrets.properties could otherwise put the
 * key back into the APK.
 */
class BuildConfigSecretsTest {

    @Test
    fun noTrufoKeyInBuildConfig() {
        val names = BuildConfig::class.java.declaredFields.map { it.name }
        assertTrue("BuildConfig still carries $names", names.none { it == "TSA_API_KEY" || it == "TSA_SERVER" })
    }
}
