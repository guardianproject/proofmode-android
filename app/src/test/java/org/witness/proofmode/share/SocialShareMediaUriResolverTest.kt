package org.witness.proofmode.share

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class SocialShareMediaUriResolverTest {

    private val original = Uri.parse("content://media/external/images/media/1")
    private val card = Uri.parse("file:///cache/share123.jpg")

    @Test
    fun watermarkOff_alwaysReturnsOriginal() {
        assertEquals(
            original,
            SocialShareMediaUriResolver.resolveShareUri(false, original, card),
        )
        assertEquals(
            original,
            SocialShareMediaUriResolver.resolveShareUri(false, original, null),
        )
    }

    @Test
    fun watermarkOn_prefersCard_fallsBackToOriginal() {
        assertEquals(
            card,
            SocialShareMediaUriResolver.resolveShareUri(true, original, card),
        )
        assertEquals(
            original,
            SocialShareMediaUriResolver.resolveShareUri(true, original, null),
        )
    }
}
