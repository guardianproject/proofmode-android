package org.witness.proofmode.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.proofset.MediaInclusion

class ShareFilebaseUploadHelperTest {

    @Test
    fun withinLimit_isNotOversize_inclusionIsIncludeMedia() {
        val length = FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES
        assertFalse(isShareUploadOversize(length))
        assertEquals(
            MediaInclusion.INCLUDE_MEDIA,
            shareUploadWithMedia(),
        )
    }

    @Test
    fun overLimit_isOversize_confirmedIsSidecarsOnly() {
        val length = FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L
        assertTrue(isShareUploadOversize(length))
        assertEquals(
            MediaInclusion.SIDECARS_ONLY,
            shareUploadSidecarsOnly(),
        )
    }

    @Test
    fun exact25MiB_isWithinMediaLimit() {
        assertTrue(
            FilebaseConfig.isWithinFilebaseMediaLimit(
                FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES,
            ),
        )
        assertFalse(
            isShareUploadOversize(
                FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES,
            ),
        )
    }

    @Test
    fun showFilebaseUploadSuccess_isUriFree_titleOnly() {
        assertNull(filebaseUploadSuccessDialogMessage("ipfs://QmAnything"))
    }
}
