package org.witness.proofmode.share

import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.proofset.MediaInclusion

/** Share → Upload within the media size limit (ignores auto include-media pref). */
internal fun shareUploadWithMedia(): MediaInclusion =
    MediaInclusion.INCLUDE_MEDIA

/** Share → Upload after user confirms proofset-without-media for oversize media. */
internal fun shareUploadSidecarsOnly(): MediaInclusion =
    MediaInclusion.SIDECARS_ONLY

/** True when media exceeds the Filebase media size limit (show proofset-without-media dialog). */
internal fun isShareUploadOversize(mediaLengthBytes: Long): Boolean =
    !FilebaseConfig.isWithinFilebaseMediaLimit(mediaLengthBytes)

/** Title-only success dialog — never surface gateway/S3 URI in the message body. */
internal fun filebaseUploadSuccessDialogMessage(uri: String): String? = null
