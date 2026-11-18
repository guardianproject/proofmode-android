package org.witness.proofmode.c2pa.tsa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.witness.proofmode.c2pa.SigningMode

/**
 * Which TSA each sign uses: the on-device TSA for local modes (ProofSign only when already
 * registered, otherwise no timestamp), ProofSign for REMOTE by default (including saved
 * values that mean the default), and a user-configured TSA called directly.
 */
class TsaSelectionTest {

    private val proofSignUrl = "https://proofsign-dev.proofmode.org/api/v1/timestamp"

    private var proxyCalled = false

    private fun select(
        chosen: SigningMode,
        effective: SigningMode = chosen,
        configured: String = "",
        registered: Boolean = false,
        local: String? = "local",
        proxy: String? = "proxy",
    ): String? = selectTsaUrl(
        chosen = chosen,
        effective = effective,
        configured = configured,
        deviceRegistered = registered,
        localTsaUrl = { local },
        proofSignProxyUrl = { proxyCalled = true; proxy },
        resolveLoopback = { "resolved:$it" },
    )

    @Test
    fun timestampUrlIsDerivedFromServer() {
        assertEquals(proofSignUrl, proofSignTimestampUrl("https://proofsign-dev.proofmode.org/"))
        assertEquals(proofSignUrl, proofSignTimestampUrl(" https://proofsign-dev.proofmode.org "))
    }

    @Test
    fun localModeUsesLocalTsa() {
        assertEquals("local", select(SigningMode.KEYSTORE, registered = true))
        assertFalse(proxyCalled)
    }

    @Test
    fun localModeFallsBackToProofSignWhenRegistered() {
        assertEquals("proxy", select(SigningMode.HARDWARE, local = null, registered = true))
    }

    @Test
    fun localModeSignsWithoutTimestampWhenUnregistered() {
        assertNull(select(SigningMode.CUSTOM, local = null, registered = false))
        assertFalse(proxyCalled)
    }

    @Test
    fun remoteDefaultUsesProofSign() {
        assertEquals("proxy", select(SigningMode.REMOTE, configured = ""))
    }

    @Test
    fun defaultRecognisesUrlVariants() {
        for (value in listOf(
            "https://tsa.trufo.ai/",
            "https://tsa.trufo.ai",
            " HTTPS://TSA.TRUFO.AI/ ",
            "https://proofsign-dev.proofmode.org/api/v1/timestamp/",
        )) {
            assertEquals(value, "proxy", select(SigningMode.REMOTE, configured = value))
        }
        assertEquals(
            "resolved:https://tsa.trufo.ai/other",
            select(SigningMode.REMOTE, configured = "https://tsa.trufo.ai/other"),
        )
    }

    @Test
    fun timestampUrlOfAnotherProofSignServerIsDefault() {
        // Saved from the settings dialog while a previous ProofSign server was configured:
        // without device headers it could only ever answer 401, so it means "default".
        assertEquals(
            "proxy",
            select(SigningMode.REMOTE, configured = "https://proofsign.proofmode.org/api/v1/timestamp"),
        )
    }

    @Test
    fun userTsaIsCalledDirectly() {
        assertEquals(
            "resolved:https://freetsa.org/tsr",
            select(SigningMode.REMOTE, configured = " https://freetsa.org/tsr "),
        )
        assertFalse(proxyCalled)
    }

    @Test
    fun remoteFallbackToKeystoreUnregisteredUsesLocal() {
        assertEquals("local", select(SigningMode.REMOTE, effective = SigningMode.KEYSTORE, registered = false))
        assertFalse(proxyCalled)
    }

    @Test
    fun remoteFallbackToKeystoreRegisteredUsesProofSign() {
        assertEquals("proxy", select(SigningMode.REMOTE, effective = SigningMode.KEYSTORE, registered = true))
    }

    @Test
    fun proxyStartFailureMeansNoTimestamp() {
        assertNull(select(SigningMode.REMOTE, proxy = null))
    }
}
