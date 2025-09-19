package org.witness.proofmode.c2pa

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import android.util.Size
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
import org.contentauth.c2pa.manifest.Action
import org.contentauth.c2pa.manifest.AttestationBuilder
import org.contentauth.c2pa.manifest.C2PAActions
import org.contentauth.c2pa.manifest.C2PAFormats
import org.contentauth.c2pa.manifest.C2PARelationships
import org.contentauth.c2pa.manifest.Ingredient
import org.contentauth.c2pa.manifest.ManifestBuilder
import org.contentauth.c2pa.manifest.ManifestHelpers
import org.contentauth.c2pa.manifest.Thumbnail
import org.contentauth.c2pa.manifest.TimestampAuthorities
import org.json.JSONObject
import org.witness.proofmode.c2pa.ThumbUtils.getThumbBitmapForMedia
import java.io.ByteArrayOutputStream
import java.util.TimeZone

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

        const val TIMESTAMP_AUTHORITY = "http://timestamp.digicert.com"

        fun init (context: Context)
        {
            //this needs to be set to
            Os.setenv("TMPDIR",context.cacheDir.absolutePath, true);

            loadSettings()
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
        suspend fun   addContentCredentials (_context: Context, _uri: Uri?, embedManifest: Boolean, allowMachineLearning: Boolean) : File {

            var filePath: String? = null
            var contentType: String = "image/jpeg"

            if (_uri != null && "content" == _uri.scheme) {
                val cursor: Cursor? = _context?.contentResolver?.query(
                    _uri,
                    arrayOf<String>(MediaStore.Images.ImageColumns.DATA),
                    null,
                    null,
                    null
                )
                cursor?.moveToFirst()
                filePath = cursor?.getString(0)
                var localType = _context?.contentResolver?.getType(_uri)
                localType?.let {
                    contentType = it
                }
                cursor?.close()
            } else {
                filePath = _uri!!.path
            }

            if (contentType.isEmpty())
                contentType = "image/jpeg"

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
                    contentType,
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

            var certPemFile = File(mContext.filesDir, C2PA_CERT_PATH)
            var keyPemFile = File(mContext.filesDir, C2PA_KEY_PATH)

            if (certPemFile.exists() && keyPemFile.exists())
                return

            // Test 1: Create a private key
            println("\n1. Creating EC private key...")
            val keyPair = CertificateSigningHelper.createPrivateKey()
            println("✓ Successfully created ${keyPair.private.algorithm} key pair")
            println("  Private key format: ${keyPair.private.format}")
            println("  Public key format: ${keyPair.public.format}")

            // Test 2: Create root certificate
            println("\n2. Creating root certificate...")
            val rootOptions = CertificateOptions(
                certificateType = CertificateType.ROOT,
                organizationName = "ProofMode Root User CA",
                email = "rootuserca@proofmode.org"
            )

            val rootCertificate = CertificateSigningHelper.createRootCertificate(rootOptions)
            println("✓ Successfully created root certificate")
            println("  Subject: ${rootCertificate.certificate.subjectX500Principal.name}")
            println("  Issuer: ${rootCertificate.certificate.issuerX500Principal.name}")
            println("  Serial: ${rootCertificate.certificate.serialNumber}")
            println("  Valid from: ${rootCertificate.certificate.notBefore}")
            println("  Valid to: ${rootCertificate.certificate.notAfter}")

            // Test 3: Create content credentials certificate
            println("\n3. Creating content credentials certificate...")
            val contentOptions = CertificateOptions(
                certificateType = CertificateType.CONTENT_CREDENTIALS,
                organizationName = "ProofMode User Content $emailAddress $pgpFingerprint",
                email = "proofmodeuser@proofmode.org"
            )
            val contentCertificate = CertificateSigningHelper.createContentCredentialsCertificate(
                rootCertificate,
                contentOptions
            )
            println("✓ Successfully created content credentials certificate")
            println("  Subject: ${contentCertificate.certificate.subjectX500Principal.name}")
            println("  Issuer: ${contentCertificate.certificate.issuerX500Principal.name}")
            println("  Serial: ${contentCertificate.certificate.serialNumber}")

            // Test 4: Verify certificate chain
            println("\n4. Verifying certificate chain...")
            try {
                contentCertificate.certificate.verify(rootCertificate.keyPair.public)
                println("✓ Content certificate successfully verified against root certificate")
            } catch (e: Exception) {
                println("✗ Certificate verification failed: ${e.message}")

            }

            // Test 5: Display PEM formats
            println("\n5. PEM Certificate (first 100 chars):")
            println("${contentCertificate.pemCertificate.take(50)}...")

            certPemFile.writeBytes(contentCertificate.pemCertificate.toByteArray())
            keyPemFile.writeBytes(contentCertificate.pemPrivateKey.toByteArray())

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

        private suspend fun addContentCredentials(mContext: Context, emailAddress: String, pgpFingerprint: String,
                                                  emailDisplay: String, webLink: String, isDirectCapture: Boolean,
                                                  allowMachineLearning: Boolean, contentType: String, fileIn: File, fileOut: File) {

            val version = C2PA.version()

            var certPemFile = File(mContext.filesDir, C2PA_CERT_PATH)

            if (!certPemFile.exists())
                initCredentials(mContext, emailAddress, pgpFingerprint)

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




            val iso8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val currentTs = iso8601.format(Date())
            val thumbnailId = fileIn.name + "-thumb.jpg"
            try {

                var mb = ManifestBuilder()
                mb.claimGenerator(appLabel, version = appVersion)
                mb.timestampAuthorityUrl(TimestampAuthorities.DIGICERT)

                mb.title(fileIn.name)
                mb.format(C2PAFormats.JPEG)
             //   mb.addThumbnail(Thumbnail(C2PAFormats.JPEG, thumbnailId))

                if (isDirectCapture)
                {
                    //add created
                    mb.addAction(Action(C2PAActions.CREATED, currentTs, appLabel))
                }
                else
                {
                    //add placed
                    mb.addAction(Action(C2PAActions.PLACED, currentTs, appLabel))

                }

                val ingredient = Ingredient(
                    title = fileIn.name,
                    format = C2PAFormats.JPEG,
                    relationship = C2PARelationships.PARENT_OF,
           //         thumbnail = Thumbnail(C2PAFormats.JPEG, thumbnailId)
                )

                mb.addIngredient(ingredient)

                val attestationBuilder = AttestationBuilder()

                attestationBuilder.addCreativeWork {
                    addAuthor(emailDisplay)
                    dateCreated(Date())
                }

                val gpsTracker = GPSTracker(mContext)
                if (showLocation == true && gpsTracker.canGetLocation()) {

                    val exifGpsVersion = "2.2.0.0"
                    var exifLat: String? = null
                    var exifLong: String? = null
                    var exifAlt: String? = null
                    var exifSpeed: String? = null
                    var exifBearing: String? = null

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

                        val locationJson = JSONObject().apply {
                            put("@type", "Place")
                            put("latitude", exifLat)
                            put("longitude", exifLong)
                            put("name", "Somewhere")
                        }

                        attestationBuilder.addAssertionMetadata {
                            dateTime(currentTs)
                            device(pgpFingerprint)
                            location(locationJson)
                        }
                    }


                }
                else {
                    attestationBuilder.addAssertionMetadata {
                        dateTime(currentTs)
                        device(pgpFingerprint)
                    }
                }


                /**
                val customAttestationJson = JSONObject().apply {
                    put("@type", "Integrity")
                    put("nonce", "something")
                    put("response", "b64encodedresponse")
                }

                attestationBuilder.addCustomAttestation("app.integrity", customAttestationJson)
                **/


                attestationBuilder.addCAWGIdentity {
                    validFromNow()
                    addSocialMediaIdentity(pgpFingerprint, webLink, currentTs, appLabel, appLabel)
                }

                attestationBuilder.buildForManifest(mb)


                val manifestJson = mb.buildJson()

                Log.d("C2PA", manifestJson)

                val builder = Builder.fromJson(manifestJson)

                try {
                    val sourceStream = FileStream(fileIn)
                    val destStream = FileStream(fileOut)

                    var certPem = File(mContext.filesDir, C2PA_CERT_PATH).readText()
                    var keyPem = File(mContext.filesDir, C2PA_KEY_PATH).readText()

                    var thumbnail = mContext.getThumbBitmapForMedia(Uri.fromFile(fileIn))
                    thumbnail?.let {
                        var baos = ByteArrayOutputStream()
                        thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                        var streamThumb = DataStream(baos.toByteArray())
                        builder.addResource(thumbnailId, streamThumb)
                    }

                    var signCallCount = 0

                    val callbackSigner = Signer.withCallback(SigningAlgorithm.ES256, certPem, TIMESTAMP_AUTHORITY) { data ->
                        signCallCount++
                        SigningHelper.signWithPEMKey(data, keyPem, "ES256")
                    }

                    try {
                        val reserveSize = callbackSigner.reserveSize()
                        val result = builder.sign(contentType, sourceStream, destStream, callbackSigner)
                        val signSucceeded = result.size > 0

                        val (manifest, signatureVerified) = if (signSucceeded) {
                            try {
                                val manifestJson = C2PA.read(fileOut)
                                Log.d("C2PA Signed",manifestJson)
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

        fun loadSettings () {
            val settingsJson = """{
                "version_major": 2,
                "version_minor": 2,
                "trust": {
                    "private_anchors": null,
                    "trust_anchors": null,
                    "trust_config": null,
                    "allowed_list": null
                },
                "Core": {
                    "debug": true,
                    "hash_alg": "sha256",
                    "salt_jumbf_boxes": true,
                    "prefer_box_hash": false,
                    "prefer_bmff_merkle_tree": false,
                    "compress_manifests": true,
                    "max_memory_usage": null
                },
                "Verify": {
                    "verify_after_reading": true,
                    "verify_after_sign": true,
                    "verify_trust": true,
                    "ocsp_fetch": false,
                    "remote_manifest_fetch": true,
                    "check_ingredient_trust": true,
                    "skip_ingredient_conflict_resolution": false,
                    "strict_v1_validation": false
                },
                "Builder": {
                    "auto_thumbnail": true
                }
            }"""

            val success = try {
                C2PA.loadSettings(settingsJson, "json")
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
