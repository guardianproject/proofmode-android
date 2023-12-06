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

        const val C2PA_CERT_PATH_PARENT = "crp.cert"
        const val C2PA_KEY_PATH_PARENT = "crp.key"

        const val CERT_VALIDITY_DAYS = 365U //5 years

        var _identityUri = "ProofMode@https://proofmode.org"
        var _identityName = "ProofMode"

        var userCert : Certificate? = null

        const val IDENTITY_URI_KEY = "id_uri"
        const val IDENTITY_NAME_KEY = "id_name"

        const val APP_ICON_URI = "https://proofmode.org/images/avatar.jpg"
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

            var fileName = fileMedia.name;
            if (!isDirectCapture)
                fileName = "c2pa-$fileName"

            var fileOut = File(fileOutDir, fileName);

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

        fun resetCredentials (mContext : Context) {

            var fileUserCert = File(mContext.filesDir, C2PA_CERT_PATH)
            var fileUserKey = File(mContext.filesDir, C2PA_KEY_PATH)

            var fileParentCert = File(mContext.filesDir, C2PA_CERT_PATH_PARENT)
            var fileParentKey = File(mContext.filesDir,C2PA_KEY_PATH_PARENT)

            fileUserKey.delete()
            fileUserCert.delete()

            fileParentCert.delete()
            fileParentKey.delete()

            userCert = null
        }
        fun initCredentials (mContext : Context, identityId: String, identityUri: String) {

            var fileUserCert = File(mContext.filesDir, C2PA_CERT_PATH)
            var fileUserKey = File(mContext.filesDir, C2PA_KEY_PATH)

            var fileParentCert = File(mContext.filesDir, C2PA_CERT_PATH_PARENT)
            var fileParentKey = File(mContext.filesDir,C2PA_KEY_PATH_PARENT)

            var emailAddress = identityId
            var pgpFingerprint = identityId

            var userKey : FileData

            if (!fileUserKey.exists()) {
                userKey = createPrivateKey()
                fileUserKey.writeBytes(userKey.getBytes())
            }
            else
            {
                userKey = FileData(fileUserKey.absolutePath,fileUserKey.readBytes(),fileUserKey.name)
            }

            if (!fileUserCert.exists()) {

                var parentKey = createPrivateKey();
                fileParentKey.writeBytes(parentKey.getBytes())

                var organization = "ProofMode Root $identityId";
                var certType = CertificateType.OfflineRoot(organization, CERT_VALIDITY_DAYS)
                var certOptions = CertificateOptions(parentKey, certType, null, null, null)

                var rootCert = createCertificate(certOptions)

                var userCertType =
                    CertificateType.ContentCredentials("ProofMode User $identityId", CERT_VALIDITY_DAYS)
                var userCertOptions = CertificateOptions(
                    userKey,
                    userCertType,
                    rootCert,
                    emailAddress,
                    pgpFingerprint
                )

                userCert = createCertificate(userCertOptions)

                userCert?.let {

                }
            }
            else
            {
                var fileDataParentKey = FileData(fileParentKey.absolutePath,fileParentKey.readBytes(),fileParentKey.name)
                var parentCert = Certificate(FileData(fileParentCert.absolutePath,fileParentCert.readBytes(),fileParentCert.name), fileDataParentKey, null)
                userCert = Certificate(FileData(fileUserCert.absolutePath,fileUserCert.readBytes(),fileUserKey.name), userKey, parentCert)
            }
        }
        fun addContentCredentials(mContext : Context, identityId: String, identityUri: String, isDirectCapture: Boolean, allowMachineLearning: Boolean, fileImageIn: File, fileImageOut: File) {

            if (userCert == null)
                initCredentials(mContext, identityId, identityUri)

            val appLabel = getAppName(mContext)
            val appVersion = getAppVersionName(mContext)
            var appIconUri = APP_ICON_URI

            var appInfo = ApplicationInfo(appLabel,appVersion,appIconUri)
            var mediaFile = FileData(fileImageIn.absolutePath, null, fileImageIn.name)
            var contentCreds = userCert?.let { ContentCredentials(it,mediaFile, appInfo) }

            if (isDirectCapture)
                contentCreds?.addCreatedAssertion()
            else
                contentCreds?.addPlacedAssertion()

            if (!allowMachineLearning)
                contentCreds?.addRestrictedAiTrainingAssertions()
            else
                contentCreds?.addPermissiveAiTrainingAssertions()

            contentCreds?.addEmailAssertion(identityId,identityId)
            contentCreds?.addPgpAssertion(identityId, identityId)
            contentCreds?.addWebsiteAssertion(identityUri)

//            contentCreds.addExifAssertion(exifData)

            contentCreds?.embedManifest(fileImageOut.absolutePath)


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
