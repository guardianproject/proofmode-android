package org.witness.proofmode.share

import android.net.Uri

object SocialShareMediaUriResolver {
    fun resolveShareUri(
        applyWatermark: Boolean,
        mediaUri: Uri,
        watermarkedUri: Uri?,
    ): Uri {
        if (!applyWatermark) return mediaUri
        return watermarkedUri ?: mediaUri
    }
}
