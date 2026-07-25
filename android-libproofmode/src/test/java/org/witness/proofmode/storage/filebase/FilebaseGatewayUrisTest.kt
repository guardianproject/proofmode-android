package org.witness.proofmode.storage.filebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FilebaseGatewayUrisTest {

    @Test
    fun buildProofsetUri_noTrailingSlash() {
        assertEquals(
            "https://ipfs.filebase.io/ipfs/bafyRoot",
            FilebaseGatewayUris.buildProofsetUri("bafyRoot"),
        )
    }

    @Test
    fun buildImageUriUnderDirectory_appendsBasename() {
        assertEquals(
            "https://ipfs.filebase.io/ipfs/bafyRoot/h.jpg",
            FilebaseGatewayUris.buildImageUriUnderDirectory("bafyRoot", "h.jpg"),
        )
    }

    @Test
    fun parseGatewayRootCid_stripsPathQueryFragment() {
        assertEquals(
            "bafyRoot",
            FilebaseGatewayUris.parseGatewayRootCid("https://ipfs.filebase.io/ipfs/bafyRoot/h.jpg?x=1#y"),
        )
        assertEquals(
            "bafyRoot",
            FilebaseGatewayUris.parseGatewayRootCid("https://ipfs.filebase.io/ipfs/bafyRoot"),
        )
        assertNull(FilebaseGatewayUris.parseGatewayRootCid("not-a-uri"))
    }

    @Test
    fun parseGatewayRootCid_appendedLines_usesLatestUriOnly() {
        // DefaultStorageProvider historically appended saveText; polluted sidecars look like this.
        val polluted =
            "https://ipfs.filebase.io/ipfs/bafyOld\n" +
                "https://ipfs.filebase.io/ipfs/bafyMid\n" +
                "https://ipfs.filebase.io/ipfs/bafyNew\n"
        assertEquals("bafyNew", FilebaseGatewayUris.parseGatewayRootCid(polluted))
        // Must not glue "cid\\nhttps:" into one arg (URLEncoder → …%3Ahttps%3A).
        assertFalse(
            FilebaseGatewayUris.parseGatewayRootCid(polluted)!!.contains(":"),
        )
        assertFalse(
            FilebaseGatewayUris.parseGatewayRootCid(polluted)!!.contains("\n"),
        )
    }

    @Test
    fun latestNonBlankLine_picksLastUri() {
        assertEquals(
            "https://ipfs.filebase.io/ipfs/bafyNew",
            FilebaseGatewayUris.latestNonBlankLine(
                "https://ipfs.filebase.io/ipfs/bafyOld\nhttps://ipfs.filebase.io/ipfs/bafyNew\n",
            ),
        )
    }

    @Test
    fun buildLeafImageUri_singleCid() {
        assertEquals(
            "https://ipfs.filebase.io/ipfs/bafyLeaf",
            FilebaseGatewayUris.buildLeafImageUri("bafyLeaf"),
        )
    }
}
