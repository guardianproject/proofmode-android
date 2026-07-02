package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetCidSidecarWriterFirstComputeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun composeByteBackedManifest_injectsMediaLinkNamedHashDotExt() {
        val hash = "mediahash001"
        val mediaBytes = "raw-media-bytes".toByteArray()
        val diskIds = listOf("$hash.proof.csv", "$hash.ipfs-cids.json", "$hash.lp.json")
        val manifestMembers = ProofSetCidMembershipPolicy.manifestMemberBasenames(
            context, hash, diskIds,
        )
        val entries = ProofSetCidManifest.composeByteBackedManifest(
            proofSetHash = hash,
            manifestMemberBasenames = manifestMembers,
            diskBytesByBasename = emptyMap(),
            mediaBytes = mediaBytes,
            mediaMimeType = "image/jpeg",
            includeOts = false,
            includeNostr = false,
        )
        assertTrue(entries.any { it.name == "$hash.jpg" && it.bytes.contentEquals(mediaBytes) })
        assertFalse(entries.any { it.name == hash })
        assertFalse(entries.any { it.name.endsWith(".ipfs-cids.json") })
    }
}
