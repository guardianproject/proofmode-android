package org.witness.proofmode.camera.c2pa

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

import info.guardianproject.simple_c2pa.*

class C2paUtils {

    companion object {

        const val C2PA_CERT_PATH = "cr.cert"
        const val C2PA_KEY_PATH = "cr.key"
        var _identityUri = "ProofMode@https://proofmode.org"
        var _identityName = "ProofMode"

        const val IDENTITY_URI_KEY = "id_uri"
        const val IDENTITY_NAME_KEY = "id_name"

        fun setC2PAIdentity (identityName: String?, identityUri: String?)
        {
            if (identityName != null) {
                _identityName = identityName
            }
            if (identityUri != null) {
                _identityUri = identityUri
            }
        }

        fun   addContentCredentials (_context: Context, _uri: Uri?, isDirectCapture: Boolean, allowMachineLearning: Boolean) {

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

            if (filePath?.isNotEmpty() == true) {
                var fileMedia = File(filePath)
                var fileOut = File(_context.cacheDir, fileMedia.name);

                if (fileMedia.exists()) {
                    //TODO add c2pa capture here
                    var identityId = _identityName
                    var identityUri = _identityUri

                    addContentCredentials(
                        _context,
                        identityId,
                        identityUri,
                        isDirectCapture,
                        allowMachineLearning,
                        fileMedia,
                        fileOut
                    )

                    copy(fileOut, fileMedia)
                    fileOut.delete()
                }
            }

        }

        @Throws(IOException::class)
        fun copy(src: File?, dst: File?) {
            val inStream = FileInputStream(src)
            val outStream = FileOutputStream(dst)
            val inChannel = inStream.channel
            val outChannel = outStream.channel
            inChannel.transferTo(0, inChannel.size(), outChannel)
            inStream.close()
            outStream.close()
        }

        fun   addContentCredentials (_context: Context, _uri: Uri?, isDirectCapture: Boolean, allowMachineLearning: Boolean, fileOutDir: File) {

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
            var fileOut = File(fileOutDir, "c2pa-" + fileMedia.name);

            if (fileMedia.exists()) {
                //TODO add c2pa capture here
                var identityId = _identityName
                var identityUri = _identityUri

                addContentCredentials(
                    _context,
                    identityId,
                    identityUri,
                    isDirectCapture,
                    allowMachineLearning,
                    fileMedia,
                    fileOut
                )
            }

        }

        fun addContentCredentials(mContext : Context, identityId: String, identityUri: String, isDirectCapture: Boolean, allowMachineLearning: Boolean, fileImageIn: File, fileImageOut: File) {

            var certPath = File(mContext.filesDir, C2PA_CERT_PATH)
            var certKey = File(mContext.filesDir, C2PA_KEY_PATH)

            var emailAddress = identityId
            var pgpFingerprint = identityId

            val rootKey = createPrivateKey();
            var certType = CertificateType.OfflineRoot("ProofMode Root $identityId", 365U)
            var certOptions = CertificateOptions(rootKey, certType, null, null, null)

            var rootCert = createCertificate(certOptions)

            var userKey = createPrivateKey()
            var userCerttype = CertificateType.ContentCredentials("ProofMode User $identityId", 365U * 5U)
            var userCertOptions = CertificateOptions(
                userKey,
                userCerttype,
                rootCert,
                emailAddress,
                pgpFingerprint
            )

            var userCert = createCertificate(userCertOptions)

            val appLabel = getAppName(mContext)
            val appVersion = getAppVersionName(mContext)
            var appIconUri = "https://proofmode.org/images/avatar.jpg"

            var appInfo = ApplicationInfo(appLabel,appVersion,appIconUri)
            var mediaFile = FileData(fileImageIn.absolutePath, null, fileImageIn.name)
            var contentCreds = ContentCredentials(userCert,mediaFile, appInfo)

            if (isDirectCapture)
                contentCreds.addCreatedAssertion()
            else
                contentCreds.addPlacedAssertion()

            if (!allowMachineLearning)
                contentCreds.addRestrictedAiTrainingAssertions()
            else
                contentCreds.addPermissiveAiTrainingAssertions()

            contentCreds.addEmailAssertion(emailAddress,emailAddress)
            contentCreds.addPgpAssertion(pgpFingerprint, emailAddress)
            contentCreds.addWebsiteAssertion(identityUri)

            contentCreds.embedManifest(fileImageOut.absolutePath)

            /**
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
                isDirectCapture,
                allowMachineLearning,
                fileImageOut.absolutePath
            )**/

        }

        fun clearContentCredentials (_context:Context) {
            var certPath = File(_context.filesDir, "cr.cert")
            var certKey = File(_context.filesDir, "cr.key")

            certPath.delete()
            certKey.delete()
        }

        fun getAppVersionName(context: Context): String {
            var appVersionName = ""
            try {
                appVersionName =
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
            return appVersionName
        }

        fun getAppName(context: Context): String {
            var appVersionName = ""
            try {
                appVersionName =
                    context.packageManager.getPackageInfo(context.packageName, 0).applicationInfo.name
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
            return appVersionName
        }
    }
}