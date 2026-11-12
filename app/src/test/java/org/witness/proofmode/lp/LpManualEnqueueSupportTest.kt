package org.witness.proofmode.lp

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class LpManualEnqueueSupportTest {

    @Test
    fun hasArtifactForManualLeg_table() {
        val offchain = LocationProtocolArtifactStore.OFFCHAIN_SUFFIX
        val onchain = LocationProtocolArtifactStore.ONCHAIN_SUFFIX
        val pending = LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX
        // (seeded suffix or null, leg, expected)
        val rows = listOf(
            Triple(offchain, LpManualLeg.OFFCHAIN, true),
            Triple(null, LpManualLeg.OFFCHAIN, false),
            Triple(onchain, LpManualLeg.ONCHAIN, true),
            Triple(pending, LpManualLeg.ONCHAIN, false),
        )
        for ((suffix, leg, expected) in rows) {
            val storage = OrchestratorTestStorage()
            suffix?.let { storage.seedArtifactIdentifier("hash1", "hash1$it") }
            assertEquals("seed=$suffix leg=$leg", expected, hasArtifactForManualLeg(storage, "hash1", leg))
        }
    }

    @Test
    fun canonicalMediaUriKey_rewritesDocumentImageToMediaImage() {
        val documentUri = Uri.parse(
            "content://com.android.providers.media.documents/document/image%3A12345",
        )
        val key = canonicalMediaUriKey(documentUri)
        assertEquals("content://media/external/images/media/12345", key)
        assertFalse(key.contains("documents/document"))
    }
}
