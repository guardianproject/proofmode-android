package org.witness.proofmode.plugins.ipfscid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaLinkNamingTest {
    private val hash = "abc123deadbeef"

    @Test fun extensionFromMimeType_jpeg_returnsJpg() {
        assertEquals("jpg", MediaLinkNaming.extensionFromMimeType("image/jpeg"))
    }

    @Test fun extensionFromMimeType_png_returnsPng() {
        assertEquals("png", MediaLinkNaming.extensionFromMimeType("image/png"))
    }

    @Test fun extensionFromMimeType_mp4_returnsMp4() {
        assertEquals("mp4", MediaLinkNaming.extensionFromMimeType("video/mp4"))
    }

    @Test fun extensionFromMimeType_nullOrUnknown_returnsBin() {
        assertEquals("bin", MediaLinkNaming.extensionFromMimeType(null))
        assertEquals("bin", MediaLinkNaming.extensionFromMimeType("image/webp"))
    }

    @Test fun manifestLinkNameForMedia_composesHashDotExt() {
        assertEquals("$hash.jpg", MediaLinkNaming.manifestLinkNameForMedia(hash, "image/jpeg"))
    }

    @Test fun findCachedMediaLeaf_prefersExtensionQualifiedKey() {
        val sidecar = SidecarSnapshot(
            rootCid = "bafy",
            files = mapOf("$hash.jpg" to "bafkMEDIA", "$hash.proof.csv" to "bafkCSV"),
            tsizes = mapOf("$hash.jpg" to 100L, "$hash.proof.csv" to 50L),
        )
        val entry = MediaLinkNaming.findCachedMediaLeaf(sidecar, hash)
        assertEquals("$hash.jpg", entry!!.name)
        assertEquals("bafkMEDIA", entry.leafCid)
        assertEquals(100L, entry.tsize)
    }

    @Test fun findCachedMediaLeaf_doesNotFallBackToBareHash() {
        val sidecar = SidecarSnapshot(
            rootCid = "bafy",
            files = mapOf(hash to "bafkLEGACY"),
            tsizes = mapOf(hash to 99L),
        )
        val entry = MediaLinkNaming.findCachedMediaLeaf(sidecar, hash)
        assertEquals(null, entry)
    }
}
