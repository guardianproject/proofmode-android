package org.witness.proofmode.c2pa

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.system.Os
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


//import info.guardianproject.simple_c2pa.*
import org.contentauth.c2pa.*

class C2paUtils {

    companion object {

        public const val C2PA_CERT_PATH = "cr.cert"
        private const val C2PA_KEY_PATH = "cr.key"

        private const val C2PA_CERT_PATH_PARENT = "crp.cert"
        private const val C2PA_KEY_PATH_PARENT = "crp.key"

        private const val ID_BACKUP_FOLDER = "certbak"

        private const val CERT_VALIDITY_DAYS = 1825U //5 years

        private var _identityUri = "https://proofmode.org"
        private var _identityName = "ProofMode"
        private var _identityEmail = "info@proofmode.org"
        private var _identityKey = "0x00000000"

       // private var userCert : Certificate? = null
        private var mPrefs : SharedPreferences? = null

        private const val APP_ICON_URI = "https://proofmode.org/images/avatar.png"

        const val PREF_OPTION_LOCATION = "trackLocation"


        fun init (context: Context)
        {
            //this needs to be set to
            Os.setenv("TMPDIR",context.cacheDir.absolutePath, true);
        }
        /**
         * Set identity values for certificate and content credentials
         */
        fun setC2PAIdentity (identityName: String?, identityUri: String?, identityEmail: String?, identityKey: String?)
        {
            if (identityName?.isNotEmpty() == true)
                _identityName = identityName.toString()

            if (identityUri?.isNotEmpty() == true)
                _identityUri = identityUri.toString()

            if (identityEmail?.isNotEmpty() == true)
                _identityEmail = identityEmail.toString()

            if (identityKey?.isNotEmpty() == true)
                _identityKey = identityKey.toString()

        }



        /**
         * Add content credentials to media from an external URI, and specify the output directory of where to stare the new file
         */
        fun   addContentCredentials (_context: Context, _uri: Uri?, embedManifest: Boolean, allowMachineLearning: Boolean) : File {

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

            val fileMedia = File(filePath!!)
            var fileName = fileMedia.name;
            var fileOut = File(filePath!!)

            if (!embedManifest) {
                //create a temporary place for the external C2PA manifest
                val ts = Date().time
                fileName = "$fileName-$ts.c2pa"
                fileOut = File(_context.cacheDir, fileName);
            }

            if (fileMedia.exists()) {

                addContentCredentials(
                    _context,
                    _identityEmail,
                    _identityKey,
                    _identityName,
                    _identityUri,
                    embedManifest,
                    allowMachineLearning,
                    fileMedia,
                    fileOut
                )

            }

            return fileOut

        }

        fun backupCredentials (mContext: Context) {
            val fileExistingCert = File(mContext.filesDir, C2PA_CERT_PATH)
            val fileExistingKey = File(mContext.filesDir, C2PA_KEY_PATH)

            if ((fileExistingCert.exists()) || (fileExistingKey.exists())) {
                backupIdentity (mContext, File(mContext.filesDir,ID_BACKUP_FOLDER), fileExistingCert, fileExistingKey)
            }
        }
        /**
         * Reset all variables and delete all local credential files
         */
        fun resetCredentials (mContext : Context) {

            val fileUserCert = File(mContext.filesDir, C2PA_CERT_PATH)
            val fileUserKey = File(mContext.filesDir, C2PA_KEY_PATH)

            val fileParentCert = File(mContext.filesDir, C2PA_CERT_PATH_PARENT)
            val fileParentKey = File(mContext.filesDir,C2PA_KEY_PATH_PARENT)

            fileUserKey.delete()
            fileUserCert.delete()

            fileParentCert.delete()
            fileParentKey.delete()

          //  userCert = null
        }

        fun importCredentials (mContext : Context, fileImportKey : File, fileImportCert : File) {

            val fileExistingCert = File(mContext.filesDir, C2PA_CERT_PATH)
            val fileExistingKey = File(mContext.filesDir, C2PA_KEY_PATH)

            backupCredentials(mContext)

            fileImportKey.copyTo(fileExistingKey,true,4096)
            fileImportCert.copyTo(fileExistingCert,true,4096)

      //      val userPrivateKey = FileData(fileExistingKey.absolutePath,fileExistingKey.readBytes(),fileImportKey.name)
        //    userCert = Certificate(FileData(fileExistingCert.absolutePath,fileExistingCert.readBytes(),fileExistingCert.name), userPrivateKey, null)

            Toast.makeText(mContext,"New certificate installed",Toast.LENGTH_LONG).show()
        }

        fun importCredentials (mContext : Context, fileImportKey : InputStream?, fileImportCert : InputStream?) {

            val fileExistingCert = File(mContext.filesDir, C2PA_CERT_PATH)
            val fileExistingKey = File(mContext.filesDir, C2PA_KEY_PATH)

            backupCredentials(mContext)

            fileImportKey?.copyTo(FileOutputStream(fileExistingKey))
            fileImportCert?.copyTo(FileOutputStream(fileExistingCert))

       //     val userPrivateKey = FileData(fileExistingKey.absolutePath,fileExistingKey.readBytes(),fileExistingKey.name)
     //       userCert = Certificate(FileData(fileExistingCert.absolutePath,fileExistingCert.readBytes(),fileExistingCert.name), userPrivateKey, null)

            Toast.makeText(mContext,"New certificate installed",Toast.LENGTH_LONG).show()
        }

        fun backupIdentity(context: Context, fileOutputDir: File, file1: File, file2: File): File? {
            // Create ZIP filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFileName = "c2pa_key_archive_$timestamp.zip"
            val zipFile = File(fileOutputDir, zipFileName)

            zipFile.parentFile?.mkdirs()

            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    listOf(file1, file2).forEach { file ->
                        FileInputStream(file).use { fis ->
                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            fis.copyTo(zos, bufferSize = 1024)
                        }
                    }
                }
                return zipFile
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return null
        }

        fun getUserCertificate (mContext : Context) : File {
            return File(mContext.filesDir, C2PA_CERT_PATH)
        }
        /**
         * initialize the private keys and certificates for signing C2PA data
         */
        fun initCredentials (mContext : Context, emailAddress: String?, pgpFingerprint: String?) {

            /**
            if (emailAddress?.isNotEmpty() == true)
                   _identityEmail = emailAddress

            if (pgpFingerprint?.isNotEmpty() == true)
                _identityKey = pgpFingerprint


            var fileUserCert = File(mContext.filesDir, C2PA_CERT_PATH)
            var fileUserKey = File(mContext.filesDir, C2PA_KEY_PATH)


            if ((!fileUserKey.exists()) || (!fileUserCert.exists())) {

                var fileParentCert = File(mContext.filesDir, C2PA_CERT_PATH_PARENT)
                var fileParentKey = File(mContext.filesDir,C2PA_KEY_PATH_PARENT)

                var parentKey = createPrivateKey();
                fileParentKey.writeBytes(parentKey.getBytes())

                var organization = "ProofMode-Root";
                var rootCert = createRootCertificate(organization, CERT_VALIDITY_DAYS)

                rootCert?.let {

                    fileParentCert.writeBytes(rootCert.getCertificateBytes())

                    var userKey = createPrivateKey()
                    fileUserKey.writeBytes(userKey.getBytes())

                    var userCertType =
                        CertificateType.ContentCredentials("ProofMode-User-$_identityKey", CERT_VALIDITY_DAYS)
                    var userCertOptions = CertificateOptions(
                        userKey,
                        userCertType,
                        rootCert,
                        _identityEmail,
                        _identityKey
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
                val userPrivateKey = FileData(fileUserKey.absolutePath,fileUserKey.readBytes(),fileUserKey.name)
                userCert = Certificate(FileData(fileUserCert.absolutePath,fileUserCert.readBytes(),fileUserKey.name), userPrivateKey, null)
            }**/
        }

        /**
         * add new C2PA Content Credential assertions and then embed and sign them
         */
        /**
        private fun addContentCredentialsSimple(mContext : Context, emailAddress: String, pgpFingerprint: String, emailDisplay: String, webLink: String, isDirectCapture: Boolean, allowMachineLearning: Boolean, fileImageIn: File, fileImageOut: File) {

            if (userCert == null)
                initCredentials(mContext, emailAddress, pgpFingerprint)

            if (mPrefs == null) mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)

            val showLocation = mPrefs?.getBoolean(
                PREF_OPTION_LOCATION,
                false
            )

            val appLabel = getAppName(mContext)
            val appVersion = getAppVersionName(mContext)

            val appInfo = ApplicationInfo(appLabel,appVersion,APP_ICON_URI)
            val mediaFile = FileData(fileImageIn.absolutePath, null, fileImageIn.name)
            val contentCreds = userCert?.let { ContentCredentials(it,mediaFile, appInfo) }

            if (isDirectCapture)
                contentCreds?.addCreatedAssertion()
            else
                contentCreds?.addPlacedAssertion()

            if (!allowMachineLearning)
                contentCreds?.addRestrictedAiTrainingAssertions()
            else
                contentCreds?.addPermissiveAiTrainingAssertions()

           // contentCreds?.addEmailAssertion(emailAddress, emailDisplay) //not yet implemented



            contentCreds?.addPgpAssertion(pgpFingerprint, pgpFingerprint)
            contentCreds?.addWebsiteAssertion(webLink)

            val exifMake = Build.MANUFACTURER
            val exifModel = Build.MODEL
            val exifTimestamp = Date().toGMTString()

            val exifGpsVersion = "2.2.0.0"
            var exifLat: String? = null
            var exifLong: String? = null
            var exifAlt: String? = null
            var exifSpeed: String? = null
            var exifBearing: String? = null

            val gpsTracker = GPSTracker(mContext)
            if (showLocation == true && gpsTracker.canGetLocation()) {

                gpsTracker.updateLocation()
                val location = gpsTracker.getLocation()
                location?.let {
                    exifLat = GPSTracker.getLatitudeAsDMS(location, 3)
                    exifLong = GPSTracker.getLongitudeAsDMS(location, 3)
                    location.altitude?.let {
                        exifAlt = location.altitude.toString()
                    }
                    location.speed?.let {
                        exifSpeed = location.speed.toString()
                    }
                    location.bearing?.let {
                        exifBearing = location.bearing.toString()
                    }
                }

            }

            val exifData = ExifData(exifGpsVersion, exifLat, exifLong, null, exifAlt, exifTimestamp, null, exifSpeed, null, null, null, exifBearing, null, null, null, null, null, exifMake, exifModel, null, null, null)
            contentCreds?.addExifAssertion(exifData)

            if (isDirectCapture)
                contentCreds?.embedManifest(fileImageOut.absolutePath)
            else
                contentCreds?.exportManifest(fileImageOut.absolutePath)

        }
**/

        private fun addContentCredentials(mContext : Context, emailAddress: String, pgpFingerprint: String, emailDisplay: String, webLink: String, isDirectCapture: Boolean, allowMachineLearning: Boolean, fileImageIn: File, fileImageOut: File) {

          //  if (userCert == null)
            //    initCredentials(mContext, emailAddress, pgpFingerprint)

            val version = C2PA.version()

            if (mPrefs == null) mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)

            val showLocation = mPrefs?.getBoolean(
                PREF_OPTION_LOCATION,
                false
            )

            val appLabel = getAppName(mContext)
            val appVersion = getAppVersionName(mContext)

            //val appInfo = ApplicationInfo(appLabel, appVersion, APP_ICON_URI)

          //  val mediaFile = FileData(fileImageIn.absolutePath, null, fileImageIn.name)
            //val contentCreds = userCert?.let { ContentCredentials(it,mediaFile, appInfo) }

            val exifMake = Build.MANUFACTURER
            val exifModel = Build.MODEL
            val exifTimestamp = Date().toGMTString()

            val exifGpsVersion = "2.2.0.0"
            var exifLat: String? = null
            var exifLong: String? = null
            var exifAlt: String? = null
            var exifSpeed: String? = null
            var exifBearing: String? = null

            val gpsTracker = GPSTracker(mContext)
            if (showLocation == true && gpsTracker.canGetLocation()) {

                gpsTracker.updateLocation()
                val location = gpsTracker.getLocation()
                location?.let {
                    exifLat = GPSTracker.getLatitudeAsDMS(location, 3)
                    exifLong = GPSTracker.getLongitudeAsDMS(location, 3)
                    location.altitude?.let {
                        exifAlt = location.altitude.toString()
                    }
                    location.speed?.let {
                        exifSpeed = location.speed.toString()
                    }
                    location.bearing?.let {
                        exifBearing = location.bearing.toString()
                    }
                }

            }

            val manifestJson = """{
                "claim_generator": "test_app/1.0",
                "assertions": [{"label": "c2pa.test", "data": {"test": true}}]
            }"""

            try {
                val builder = Builder.fromJson(manifestJson)
                try {
                    val sourceStream = FileStream(fileImageIn)
             //       val fileTest = File.createTempFile("c2pa-callback-test", ".jpg")
                    val destStream = FileStream(fileImageOut)

                    var certPem = File(mContext.filesDir, C2PA_CERT_PATH).readText()
                    var keyPem = File(mContext.filesDir, C2PA_KEY_PATH).readText()

                    var signCallCount = 0

                    val callbackSigner = Signer.withCallback(SigningAlgorithm.ES256, certPem, null) { data ->
                        signCallCount++
                        SigningHelper.signWithPEMKey(data, keyPem, "ES256")
                    }

                    try {
                        val reserveSize = callbackSigner.reserveSize()
                        val result = builder.sign("image/jpeg", sourceStream, destStream, callbackSigner)
                        val signSucceeded = result.size > 0

                        val (manifest, signatureVerified) = if (signSucceeded) {
                            try {
                                val manifestJson = C2PA.read(fileImageOut)
                                if (manifestJson != null) {
                                    Pair(manifestJson, true)
                                } else {
                                    Pair(null, false)
                                }
                            } catch (e: Exception) {
                                Pair(null, false)
                            }
                        } else {
                            Pair(null, false)
                        }

                        val success = signCallCount > 0 &&
                                reserveSize > 0 &&
                                signSucceeded &&
                                signatureVerified

                    } finally {
                        callbackSigner.close()
                        sourceStream.close()
                        destStream.close()
                    }
                } finally {
                    builder.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }


        /**
         * Helper functions for getting app name and version
         */
        fun getAppVersionName(context: Context): String {
            var appVersionName = ""
            try {
                appVersionName =
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName?:""
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
            return appVersionName
        }

        fun getAppName(context: Context): String {
            var appVersionName = ""
            try {
                appVersionName =
                    context.packageManager.getPackageInfo(context.packageName, 0).applicationInfo?.name?:""
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
