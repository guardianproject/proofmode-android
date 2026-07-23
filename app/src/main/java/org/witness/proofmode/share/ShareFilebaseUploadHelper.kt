package org.witness.proofmode.share

import org.witness.proofmode.storage.proofset.MediaInclusion

/** Share → Upload to Filebase always includes media; ignores auto-upload include pref. */
internal fun mediaInclusionForShareUpload(): MediaInclusion = MediaInclusion.INCLUDE_MEDIA

/** Title-only success dialog — never surface gateway/S3 URI in the message body. */
internal fun filebaseUploadSuccessDialogMessage(uri: String): String? = null
