package org.witness.proofmode.lp

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class LpManualEnqueueSupportTest {

    private val storage = OrchestratorTestStorage()

    @Test
    fun hasArtifactForManualLeg_offchain_trueWhenOffchainOrLegacyExists() {
        storage.seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.OFFCHAIN_SUFFIX}")
        assertTrue(hasArtifactForManualLeg(storage, "hash1", LpManualLeg.OFFCHAIN))
    }

    @Test
    fun hasArtifactForManualLeg_offchain_trueWhenLegacyExists() {
        storage.seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.LEGACY_SUFFIX}")
        assertTrue(hasArtifactForManualLeg(storage, "hash1", LpManualLeg.OFFCHAIN))
    }

    @Test
    fun hasArtifactForManualLeg_onchain_trueWhenOnchainOrPendingExists() {
        storage.seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX}")
        assertTrue(hasArtifactForManualLeg(storage, "hash1", LpManualLeg.ONCHAIN))
    }

    @Test
    fun hasArtifactForManualLeg_onchain_trueWhenOnchainArtifactExists() {
        storage.seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}")
        assertTrue(hasArtifactForManualLeg(storage, "hash1", LpManualLeg.ONCHAIN))
    }

    @Test
    fun hasArtifactForManualLeg_returnsFalseWhenNoMatchingArtifact() {
        assertFalse(hasArtifactForManualLeg(storage, "hash1", LpManualLeg.OFFCHAIN))
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

    @Test
    fun collectShareProofMediaUris_sendMultiple_returnsAllStreams() {
        val uri1 = Uri.parse("content://test/1")
        val uri2 = Uri.parse("content://test/2")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2))
        }
        val uris = collectShareProofMediaUris(intent) { it }
        assertEquals(listOf(uri1, uri2), uris)
    }

    @Test
    fun collectShareProofMediaUris_send_singleExtraStream() {
        val uri = Uri.parse("content://test/1")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertEquals(listOf(uri), collectShareProofMediaUris(intent) { it })
    }
}
