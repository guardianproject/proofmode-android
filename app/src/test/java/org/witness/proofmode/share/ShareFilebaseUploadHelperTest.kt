package org.witness.proofmode.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.witness.proofmode.storage.proofset.MediaInclusion

/** JVM unit tests for Share → Filebase upload helpers (no Robolectric). */
class ShareFilebaseUploadHelperTest {

    @Test
    fun shareUpload_alwaysIncludeMedia_evenWhenAutoIncludePrefFalse() {
        assertEquals(MediaInclusion.INCLUDE_MEDIA, mediaInclusionForShareUpload())
    }

    @Test
    fun showFilebaseUploadSuccess_isUriFree_titleOnly() {
        assertNull(filebaseUploadSuccessDialogMessage("ipfs://QmAnything"))
    }
}
