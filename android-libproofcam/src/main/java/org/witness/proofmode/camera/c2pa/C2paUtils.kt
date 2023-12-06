package org.witness.proofmode.camera.c2pa

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import info.guardianproject.simple_c2pa.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date

class C2paUtils {

    companion object {

        private const val C2PA_CERT_PATH = "cr.cert"
        private const val C2PA_KEY_PATH = "cr.key"

        private const val C2PA_CERT_PATH_PARENT = "crp.cert"
        private const val C2PA_KEY_PATH_PARENT = "crp.key"

        private const val CERT_VALIDITY_DAYS = 365U //5 years

        private var _identityUri = "ProofMode@https://proofmode.org"
        private var _identityName = "ProofMode"

        private var userCert : Certificate? = null

        const val IDENTITY_URI_KEY = "id_uri"
        const val IDENTITY_NAME_KEY = "id_name"
        const val IDENTITY_EMAIL_KEY = "id_email"
        const val IDENTITY_PGP_KEY = "id_pgp"

        private const val APP_ICON_URI = "https://proofmode.org/images/avatar.jpg"

        /**
         * Set identity values for certificate and content credentials
         */
        fun setC2PAIdentity (identityName: String?, identityUri: String?)
        {
            if (identityName != null) {
                _identityName = identityName
            }
            if (identityUri != null) {
                _identityUri = identityUri
            }
        }

        /**
         * Add content credentials to media from an external URI
         */
        fun   addContentCredentials (_context: Context, _uri: Uri?, isDirectCapture: Boolean, allowMachineLearning: Boolean) {

            addContentCredentials(_context, _uri, isDirectCapture, allowMachineLearning, _context.cacheDir)

        }


        /**
         * Add content credentials to media from an external URI, and specify the output directory of where to stare the new file
         */
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
            var fileOut = fileMedia
            var fileName = fileMedia.name;

            if (!isDirectCapture) {
                fileName = "c2pa-$fileName"
                fileOut = File(fileOutDir, fileName);
            }

            if (fileMedia.exists()) {
                //TODO add c2pa capture here
                var identityId = _identityName
                var identityUri = _identityUri

                addContentCredentials(
                    _context,
                    identityId,
                    identityUri,
                    identityId,
                    identityUri,
                    isDirectCapture,
                    allowMachineLearning,
                    fileMedia,
                    fileOut
                )
            }

        }

        /**
         * Reset all variables and delete all local credential files
         */
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

        /**
         * initialize the private keys and certificates for signing C2PA data
         */
        fun initCredentials (mContext : Context, emailAddress: String, pgpFingerprint: String) {

            var fileUserCert = File(mContext.filesDir, C2PA_CERT_PATH)
            var fileUserKey = File(mContext.filesDir, C2PA_KEY_PATH)

            var fileParentCert = File(mContext.filesDir, C2PA_CERT_PATH_PARENT)
            var fileParentKey = File(mContext.filesDir,C2PA_KEY_PATH_PARENT)

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

                var organization = "ProofMode-Root";
                var rootCert = createRootCertificate(organization, CERT_VALIDITY_DAYS)

                rootCert?.let {

                    fileParentCert.writeBytes(rootCert.getCertificateBytes())

                    var userCertType =
                        CertificateType.ContentCredentials("ProofMode-User", CERT_VALIDITY_DAYS)
                    var userCertOptions = CertificateOptions(
                        userKey,
                        userCertType,
                        rootCert,
                        "test",
                        "test"
                    )

                    userCert = createCertificate(userCertOptions)

                    userCert?.let {

                        //this is where we would save the cert data once we have access to it
                        fileUserCert.writeBytes(it.getCertificateBytes())

                    }
                }

            }
            else
            {
                var fileDataParentKey = FileData(fileParentKey.absolutePath,fileParentKey.readBytes(),fileParentKey.name)
                var parentCert = Certificate(FileData(fileParentCert.absolutePath,fileParentCert.readBytes(),fileParentCert.name), fileDataParentKey, null)
                userCert = Certificate(FileData(fileUserCert.absolutePath,fileUserCert.readBytes(),fileUserKey.name), userKey, parentCert)
            }
        }

        /**
         * add new C2PA Content Credential assertions and then embed and sign them
         */
        fun addContentCredentials(mContext : Context, emailAddress: String, pgpFingerprint: String, emailDisplay: String?, webLink: String?, isDirectCapture: Boolean, allowMachineLearning: Boolean, fileImageIn: File, fileImageOut: File) {

            if (userCert == null)
                initCredentials(mContext, emailAddress, pgpFingerprint)

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

            emailDisplay?.let { contentCreds?.addEmailAssertion(emailAddress, it) }
            pgpFingerprint?.let { contentCreds?.addPgpAssertion(it, it) }
            webLink?.let { contentCreds?.addWebsiteAssertion(it) }

            /**
             * let exif_data = ExifData {
             *             gps_version_id: Some("2.2.0.0".to_string()),
             *             latitude: Some("39,21.102N".to_string()),
             *             longitude: Some("74,26.5737W".to_string()),
             *             altitude_ref: Some(0),
             *             altitude: Some("100963/29890".to_string()),
             *             timestamp: Some("2019-09-22T18:22:57Z".to_string()),
             *             speed_ref: Some("K".to_string()),
             *             speed: Some("4009/161323".to_string()),
             *             direction_ref: Some("T".to_string()),
             *             direction: Some("296140/911".to_string()),
             *             destination_bearing_ref: Some("T".to_string()),
             *             destination_bearing: Some("296140/911".to_string()),
             *             positioning_error: Some("13244/2207".to_string()),
             *             exposure_time: Some("1/100".to_string()),
             *             f_number: Some(4.0),
             *             color_space: Some(1),
             *             digital_zoom_ratio: Some(2.0),
             *             make: Some("ProofMode".to_string()),
             *             model: Some("ProofMode In-App Camera v2.0".to_string()),
             *             lens_make: Some("CameraCompany".to_string()),
             *             lens_model: Some("17.0-35.0 mm".to_string()),
             *             lens_specification: Some(vec![1.55, 4.2, 1.6, 2.4]),
             *         };
             *
             */

            var exifMake = Build.MANUFACTURER
            var exifModel = Build.MODEL
            var exifTimestamp = Date().toGMTString()

            var exifGpsVersion = "2.2.0.0"
            var exifLat: String? = null
            var exifLong: String? = null

            var gpsTracker = GPSTracker(mContext)
            gpsTracker.updateLocation()
            var location = gpsTracker.getLocation()
            location?.let {
                exifLat = GPSTracker.getLatitudeAsDMS(location, 3)
                exifLong = GPSTracker.getLongitudeAsDMS(location, 3)
            }

            var exifData = ExifData(exifGpsVersion, exifLat, exifLong, null, null, exifTimestamp, null, null, null, null, null, null, null, null, null, null, null, exifMake, exifModel, null, null, null)
            contentCreds?.addExifAssertion(exifData)

            contentCreds?.embedManifest(fileImageOut.absolutePath)


        }


        /**
         * Helper functions for getting app name and version
         */
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
    }
}
