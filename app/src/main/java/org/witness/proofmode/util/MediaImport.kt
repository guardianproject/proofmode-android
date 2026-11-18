package org.witness.proofmode.util

import android.content.Context
import android.net.Uri
import org.witness.proofmode.ProofMode
import org.witness.proofmode.R
import org.witness.proofmode.importing.ImportMediaValidator.Reason
import timber.log.Timber

/**
 * Imports existing media straight into the feed, with no confirmation step. MediaWatcher screens
 * each file (ImportMediaValidator), signs a private copy and broadcasts
 * PROOF_GENERATED_IMPORT, which adds the copy to the feed; a refused file instead broadcasts
 * PROOF_IMPORT_REJECTED, which MainActivity turns into a dialog.
 */
object MediaImport {

    fun importMedia(context: Context, uris: List<Uri>) {
        for (uri in uris) {
            try {
                val mimeType = context.contentResolver.getType(uri)
                ProofMode.generateProofForImport(context, uri, null, mimeType)
            } catch (e: Exception) {
                Timber.w(e, "Could not queue import of %s", uri)
            }
        }
    }

    /** Dialog text for [rejected] of [total] refused files and the reasons they were refused. */
    fun rejectionMessage(context: Context, reasons: Collection<Reason>, rejected: Int, total: Int): String {
        val intro = context.getString(R.string.import_rejected_intro)
        val lines = reasons.joinToString("\n") { "• " + context.getString(reasonText(it)) }
        return if (total > 1) {
            "$intro\n\n${context.getString(R.string.import_rejected_multi, rejected, total)}\n$lines"
        } else {
            "$intro\n\n$lines"
        }
    }

    fun parseReasons(names: Array<String>?): List<Reason> =
        names.orEmpty().mapNotNull { name -> Reason.entries.firstOrNull { it.name == name } }

    private fun reasonText(reason: Reason): Int = when (reason) {
        Reason.SYNTHETIC_SOURCE -> R.string.import_reject_synthetic
        Reason.INVALID_C2PA -> R.string.import_reject_invalid_c2pa
        Reason.MISSING_CAMERA_EXIF -> R.string.import_reject_missing_exif
        Reason.EDITING_SOFTWARE -> R.string.import_reject_editing_software
        Reason.MISSING_VIDEO_METADATA -> R.string.import_reject_missing_video_metadata
        Reason.UNUSUAL_ASPECT_RATIO -> R.string.import_reject_aspect_ratio
        Reason.UNSUPPORTED_TYPE -> R.string.import_reject_unsupported
        Reason.UNREADABLE -> R.string.import_reject_unreadable
    }
}
