package org.witness.proofmode.camera.c2pa

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import okhttp3.internal.ignoreIoExceptions
import java.io.File
import org.proofmode.c2pa.C2paJNI

class C2paUtils {

    companion object {

        const val C2PA_CERT_PATH = "cr.cert"
        const val C2PA_KEY_PATH = "cr.key"
        var _identityUri = "ProofMode@https://proofmode.org"
        var _identityName = "ProofMode"

        const val IDENTITY_URI_KEY = "ProofMode@https://proofmode.org"
        const val IDENTITY_NAME_KEY = "ProofMode"

        fun setC2PAIdentity (identityName: String?, identityUri: String?)
        {
            if (identityName != null) {
                _identityUri = identityName
            }
            if (identityUri != null) {
                _identityName = identityUri
            }
        }

        fun addContentCredentials (_context: Context, _uri: Uri?) {

            var filePath: String? = null
            if (_uri != null && "content" == _uri.scheme) {
                val cursor: Cursor? = _context?.getContentResolver()?.query(
                    _uri,
                    arrayOf<String>(MediaStore.Images.ImageColumns.DATA),
                    null,
                    null,
                    null
                )
                cursor?.moveToFirst()
                filePath = cursor?.getString(0)
                cursor?.close()
            } else {
                filePath = _uri!!.path
            }

            var fileMedia = File(filePath)

            if (fileMedia.exists()) {
                //TODO add c2pa capture here
                var identityId = _identityName
                var identityUri = _identityUri

                C2paUtils.addContentCredentials(
                    _context,
                    identityId,
                    identityUri,
                    fileMedia,
                    fileMedia
                )
            }

        }
        fun addContentCredentials(mContext : Context, identityId: String, identityUri: String, fileImageIn: File, fileImageOut: File) {

            var certPath = File(mContext.filesDir, C2PA_CERT_PATH)
            var certKey = File(mContext.filesDir, C2PA_KEY_PATH)

            if (!certPath.exists() || !certKey.exists())
                C2paJNI.generateCredentials(
                    certPath.absolutePath,
                    certKey.absolutePath,
                    identityUri
                )

            C2paJNI.addAssert(
                certPath.absolutePath,
                certKey.absolutePath,
                fileImageIn.absolutePath,
                identityUri,
                identityId,
                fileImageOut.absolutePath
            )

        }

        fun clearContentCredentials (_context:Context) {
            var certPath = File(_context.filesDir, "cr.cert")
            var certKey = File(_context.filesDir, "cr.key")

            certPath.delete()
            certKey.delete()
        }
    }
}