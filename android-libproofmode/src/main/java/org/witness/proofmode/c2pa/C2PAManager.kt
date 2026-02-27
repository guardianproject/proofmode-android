package org.witness.proofmode.c2pa

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Environment
import android.preference.PreferenceManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.WrappedKeyEntry
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.contentauth.c2pa.Action
import org.contentauth.c2pa.Builder
import org.contentauth.c2pa.BuilderIntent
import org.contentauth.c2pa.C2PA
import org.contentauth.c2pa.C2PASettings
import org.contentauth.c2pa.CertificateManager
import org.contentauth.c2pa.DigitalSourceType
import org.contentauth.c2pa.FileStream
import org.contentauth.c2pa.KeyStoreSigner
import org.contentauth.c2pa.PredefinedAction
import org.contentauth.c2pa.Signer
import org.contentauth.c2pa.SigningAlgorithm
import org.contentauth.c2pa.Stream
import org.contentauth.c2pa.StrongBoxSigner
import org.contentauth.c2pa.WebServiceSigner
import org.contentauth.c2pa.manifest.AssertionDefinition
import org.contentauth.c2pa.manifest.CawgTrainingMiningEntry
import org.contentauth.c2pa.manifest.ClaimGeneratorInfo
import org.contentauth.c2pa.manifest.ManifestDefinition
import org.contentauth.c2pa.manifest.ManifestValidator
import org.json.JSONObject
import org.witness.proofmode.ProofMode
import org.witness.proofmode.ProofMode.PREF_OPTION_LOCATION
import org.witness.proofmode.c2pa.selfsign.CertificateSigningService
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.library.BuildConfig
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

class C2PAManager(private val context: Context, private val preferencesManager: PreferencesManager) {
    companion object {
        private const val TAG = "C2PAManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS_PREFIX = "C2PA_KEY_"

        /**
        private val iso8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }**/

//        private const val TSA_DIGICERT = "http://timestamp.digicert.com"
        private const val TSA_SSLCOM = "https://api.c2patool.io/api/v1/timestamps/ecc"
    }

    private var defaultSigner: Signer? = null



    suspend fun signMediaFile(signingMode: SigningMode, inFile: File, contentType: String, outFile: File, doEmbed: Boolean = true): Result<Stream> = withContext(Dispatchers.IO) {
        try {


            Timber.d( "Original file size: ${inFile.length()} bytes")

           Timber.d( "Using signing mode: $signingMode")

            val pPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val email = pPrefs.getString(ProofMode.PREF_CREDENTIALS_PRIMARY,"info@proofmode.org")
            val blockAI = pPrefs?.getBoolean(ProofMode.PREF_OPTION_BLOCK_AI, ProofMode.PREF_OPTION_AI_DEFAULT)

            val showLocation = pPrefs?.getBoolean(
                PREF_OPTION_LOCATION,
                true
            )

            var location : Location? = null;

            if (showLocation == true)
                location = ProofMode.getLatestLocation(context);

            val manifest = ManifestDefinition(
                title = inFile.name,
                claimGeneratorInfo = listOf(ClaimGeneratorInfo.fromContext(context)),
                assertions = listOf(
                    createExifAssertion(context, location, inFile, contentType),
                    createProofmodeAssertion(context, inFile),
                    createCAWGAssertion(context, blockAI == true, email)
                )
            );

            var manifestJSON = manifest.toJson()
            Timber.d("Media manifest file:\n\n$manifestJSON")

            // Create appropriate signer based on mode
            if (defaultSigner == null)
                defaultSigner = createSigner(signingMode, TSA_SSLCOM)

            defaultSigner?.let {
                // Sign the image using C2PA library
                val fileStream = FileStream(inFile)
                val outStream = FileStream(outFile)

                signStream(
                    inFile.name,
                    fileStream,
                    contentType,
                    outStream,
                    manifestJSON,
                    it,
                    doEmbed
                )
                Timber.d("Signed file size: ${outFile.length()} bytes")

                // Verify the signed image
                var isVerified = validateSignedMedia(outFile.absolutePath)
                Timber.d("isVerified=$isVerified")

                Result.Success(outStream)
            }
            Result.Failure("Error signing image: no signer available",null)

        } catch (e: Exception) {
            Log.e(TAG, "Error signing image", e)
            e.printStackTrace()
            Result.Failure("Error signing image",e)
        }
    }

    fun setupProofSign () {

        val client = ProofSignClient(context,
            BuildConfig.SIGNING_SERVER_ENDPOINT,
            BuildConfig.CLOUD_INTEGRITY_PROJECT_NUMBER,
            BuildConfig.SIGNING_SERVER_TOKEN
        );
    }

    private suspend fun createSigner(mode: SigningMode, tsaUrl: String): Signer? = withContext(Dispatchers.IO) {
        when (mode) {
            SigningMode.KEYSTORE -> createKeystoreSigner(tsaUrl)
            SigningMode.HARDWARE -> createHardwareSigner(tsaUrl)
            SigningMode.CUSTOM -> createCustomSigner(tsaUrl)
            SigningMode.REMOTE -> createRemoteSigner()
        }
    }

    private suspend fun createKeystoreSigner(tsaUrl: String): Signer {
        val keyAlias = "C2PA_SOFTWARE_KEY_SECURE"
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        var certChain = ""

        // Create or get the keystore key
        if (!keyStore.containsAlias(keyAlias)) {
            Timber.d( "Creating new keystore key")
            createKeystoreKey(keyAlias, false)

            // Get certificate chain from signing server
            certChain = enrollHardwareKeyCertificate(keyAlias)

            var fileCert = File(context.filesDir,"$keyAlias.cert")
            fileCert.writeText(certChain)
        }
        else{
            // Get certificate chain from signing server

            val fileCert = File(context.filesDir,"$keyAlias.cert")
            if (fileCert.exists())
                certChain = fileCert.readText()
            else {
                certChain = enrollHardwareKeyCertificate(keyAlias)
                fileCert.writeText(certChain)
            }

        }


        Timber.d( "Using KeyStoreSigner with keyAlias: $keyAlias")

        // Use the new KeyStoreSigner class
        return KeyStoreSigner.createSigner(
            algorithm = SigningAlgorithm.ES256,
            certificateChainPEM = certChain,
            keyAlias = keyAlias,
            tsaURL = tsaUrl
        )
    }


    private suspend fun createHardwareSigner(tsaUrl: String): Signer? {
        val keyAlias =
            preferencesManager.hardwareKeyAlias.first()
                ?: "$KEYSTORE_ALIAS_PREFIX${SigningMode.HARDWARE.name}"

        // Get or create hardware-backed key
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        var certChain : String = ""

        if (!keyStore.containsAlias(keyAlias)) {
            Timber.d( "Creating new hardware-backed key with StrongBox if available")

            // Create StrongBox config
            val config = StrongBoxSigner.Config(keyTag = keyAlias, requireUserAuthentication = false)

            // Create key using StrongBoxSigner (will use StrongBox if available, TEE otherwise)
            try {
                StrongBoxSigner.createKey(config)
                preferencesManager.setHardwareKeyAlias(keyAlias)
            } catch (e: Exception) {
                Timber.d( "StrongBox key creation failed, falling back to hardware-backed key")
                createKeystoreKey(keyAlias, true)
                preferencesManager.setHardwareKeyAlias(keyAlias)
            }
            // Get certificate chain from signing server
            certChain = enrollHardwareKeyCertificate(keyAlias)

            var fileCert = File(context.filesDir,"$keyAlias.cert")
            fileCert.writeText(certChain)
        }

        else{
            // Get certificate chain from signing server

            val fileCert = File(context.filesDir,"$keyAlias.cert")
            if (fileCert.exists())
                certChain = fileCert.readText()

        }


        if (certChain.isNotEmpty()) {
            Timber.d("Creating StrongBoxSigner")

            // Create StrongBox config
            val config = StrongBoxSigner.Config(keyTag = keyAlias, requireUserAuthentication = false)

            // Use the new StrongBoxSigner class
            return StrongBoxSigner.createSigner(
                algorithm = SigningAlgorithm.ES256,
                certificateChainPEM = certChain,
                config = config,
                tsaURL = tsaUrl
            )
        }
        else
            return null
    }

    private suspend fun createCustomSigner(tsaUrl: String): Signer {
        val certPEM =
            preferencesManager.customCertificate.first()
                ?: throw IllegalStateException("Custom certificate not configured")
        val keyPEM =
            preferencesManager.customPrivateKey.first()
                ?: throw IllegalStateException("Custom private key not configured")

        val keyAlias = "C2PA_CUSTOM_KEY_SECURE"
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Check if we need to reimport (e.g., user uploaded new key)
        val lastKeyHash = preferencesManager.customKeyHash.first()
        val currentKeyHash = keyPEM.hashCode().toString()

        if (!keyStore.containsAlias(keyAlias) || lastKeyHash != currentKeyHash) {
            Timber.d( "Importing custom private key into Android Keystore")

            // Remove old key if exists
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }

            // Try to import, fallback to direct key usage if it fails
            try {
                importKeySecurely(keyAlias, keyPEM)
                Timber.d( "Successfully imported custom key using Secure Key Import")
                preferencesManager.setCustomKeyHash(currentKeyHash)
            } catch (e: Exception) {
                Log.w(TAG, "Custom key import failed, using direct key: ${e.message}")
                // Fallback to direct key usage
                return Signer.fromKeys(
                    certsPEM = certPEM,
                    privateKeyPEM = keyPEM,
                    algorithm = SigningAlgorithm.ES256,
                    tsaURL = tsaUrl
                )
            }
        }

        Timber.d( "Creating custom signer with KeyStoreSigner")

        // Use the new KeyStoreSigner class
        return KeyStoreSigner.createSigner(
            algorithm = SigningAlgorithm.ES256,
            certificateChainPEM = certPEM,
            keyAlias = keyAlias,
            tsaURL = tsaUrl,
        )
    }

    private suspend fun createRemoteSigner(): Signer {

        val configUrl = BuildConfig.SIGNING_SERVER_AND_ENDPOINT
        val bearerToken = BuildConfig.SIGNING_SERVER_TOKEN

        Timber.d( "Creating WebServiceSigner with URL: $configUrl")

        // Use the new WebServiceSigner class
        val webServiceSigner =
            WebServiceSigner(configurationURL = configUrl, bearerToken = bearerToken)

        return webServiceSigner.createSigner()
    }

    private fun createKeystoreKey(alias: String, useHardware: Boolean) {
        val keyPairGenerator =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)

        val paramSpec =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .apply {
                    setDigests(KeyProperties.DIGEST_SHA256)
                    setAlgorithmParameterSpec(
                        ECGenParameterSpec("secp256r1"),
                    )

                    if (useHardware) {
                        // Request hardware backing (StrongBox if available, TEE otherwise)
                        if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.P
                        ) {
                            setIsStrongBoxBacked(true)
                        }
                    }

                    // Self-signed certificate validity
                    setCertificateSubject(
                        X500Principal("CN=C2PA Android User, O=C2PA Example, C=US"),
                    )
                    setCertificateSerialNumber(
                        BigInteger.valueOf(System.currentTimeMillis()),
                    )
                    setCertificateNotBefore(Date())
                    setCertificateNotAfter(
                        Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000),
                    )
                }
                .build()

        keyPairGenerator.initialize(paramSpec)
        keyPairGenerator.generateKeyPair()
    }

    private suspend fun enrollHardwareKeyCertificate(alias: String): String {

        // Generate CSR for the hardware key
        val csr = generateCSR(alias)

        // Submit CSR to signing server
        val csrResp = CertificateSigningService().signCSR(csr)
        val certChain = csrResp.certificate_chain
        val certId = csrResp.certificate_id

        Timber.d( "Certificate enrolled successfully. ID: $certId")

        return certChain
    }

    private fun generateCSR(alias: String): String {
        try {
            // Use the library's CertificateManager to generate a proper CSR
            val config =
                CertificateManager.CertificateConfig(
                    commonName = "Proofmode C2PA Hardware Key",
                    organization = "Proofmode App Self-Signed",
                    organizationalUnit = "Mobile",
                    country = "US",
                    state = "New York",
                    locality = "New York",
                )

            // Generate CSR using the library
            val csr = CertificateManager.createCSR(alias, config)

            Timber.d( "Generated proper CSR for alias $alias")
            return csr
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate CSR", e)
            throw RuntimeException("Failed to generate CSR: ${e.message}", e)
        }
    }

    /** Import key using Secure Key Import (API 28+) Throws exception if import fails */
    private fun importKeySecurely(keyAlias: String, privateKeyPEM: String) {
        try {
            Timber.d( "Starting key import for alias: $keyAlias")
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            // Parse the private key from PEM
            val privateKeyBytes = parsePrivateKeyFromPEM(privateKeyPEM)
            val keyFactory = KeyFactory.getInstance("EC")
            val privateKey =
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as
                    ECPrivateKey

            Timber.d( "Private key parsed, algorithm: ${privateKey.algorithm}")

            // Create wrapping key for import (using ENCRYPT/DECRYPT which is more widely supported)
            val wrappingKeyAlias = "${keyAlias}_WRAPPER_TEMP"

            // Clean up any existing wrapper key
            if (keyStore.containsAlias(wrappingKeyAlias)) {
                keyStore.deleteEntry(wrappingKeyAlias)
            }

            // Generate RSA wrapping key with ENCRYPT purpose (more compatible than WRAP_KEY)
            val keyGenSpec =
                KeyGenParameterSpec.Builder(
                    wrappingKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(2048)
                    .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .build()

            val keyPairGenerator =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            keyPairGenerator.initialize(keyGenSpec)
            val wrappingKeyPair = keyPairGenerator.generateKeyPair()
            Timber.d( "Wrapping key generated")

            // Get the public key for wrapping
            val publicKey = wrappingKeyPair.public

            // Wrap the private key
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(Cipher.WRAP_MODE, publicKey)
            val wrappedKeyBytes = cipher.wrap(privateKey)
            Timber.d( "Key wrapped, bytes length: ${wrappedKeyBytes.size}")

            // Import using WrappedKeyEntry
            val importSpec =
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(
                        ECGenParameterSpec("secp256r1"),
                    )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()

            val wrappedKeyEntry =
                WrappedKeyEntry(
                    wrappedKeyBytes,
                    wrappingKeyAlias,
                    "RSA/ECB/OAEPPadding",
                    importSpec,
                )

            keyStore.setEntry(keyAlias, wrappedKeyEntry, null)
            Timber.d( "Key imported to keystore")

            // Clean up wrapping key
            keyStore.deleteEntry(wrappingKeyAlias)

            // Verify import
            if (keyStore.containsAlias(keyAlias)) {
                Timber.d( "Key successfully imported and verified in keystore")
            } else {
                throw IllegalStateException("Key not found after import")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Key import failed", e)
            Log.e(TAG, "Exception: ${e.javaClass.name}: ${e.message}")
            // Don't generate a wrong key - just fail and let the caller handle it
            throw IllegalStateException(
                "Failed to import key using Secure Key Import: ${e.message}",
                e,
            )
        }
    }

    /** Parse private key from PEM format */
    private fun parsePrivateKeyFromPEM(pem: String): ByteArray {
        val pemContent =
            pem.replace("-----BEGIN EC PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END EC PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")

        return Base64.decode(pemContent, Base64.NO_WRAP)
    }

    private fun signStream(fileName: String, sourceStream: Stream, contentType: String, destStream: Stream, manifestJSON: String, signer: Signer, embed: Boolean = true) {
        Timber.d( "Starting signImageData")
        Timber.d( "Manifest JSON: ${manifestJSON.take(200)}...") // First 200 chars

        val createdLabels = (Builder.DEFAULT_CREATED_ASSERTION_LABELS + listOf(
            "proofmode.metadata",
        )).joinToString(", ") { "\"$it\"" }

        val settingsJson = """
            {
                "version": 1,
                "builder": {
                    "created_assertion_labels": [$createdLabels]
                }
            }
        """.trimIndent()

        val settings = C2PASettings.create().apply {
            updateFromString(settingsJson, "json")
        }

        val builder = Builder.fromJson(manifestJSON, settings)
        settings.close()
        if (!embed)
            builder.setNoEmbed()

        val appLabel = getAppName(context)
        val appVersion = getAppVersionName(context)
        var mParams = null//HashMap<String, String>()

        var softwareAgent = "$appLabel-$appVersion";// ${android.os.Build.VERSION.SDK_INT.toString()} ${android.os.Build.VERSION.CODENAME}";
        var action = Action(
            PredefinedAction.CREATED,
            DigitalSourceType.DIGITAL_CAPTURE,
            softwareAgent,
            mParams
        )
        builder.addAction(action)

        builder.setIntent(BuilderIntent.Create(DigitalSourceType.DIGITAL_CAPTURE))

        val ingredientJson = JSONObject().apply {
            put("title", fileName)
            put("format", contentType)
        }
        builder.addIngredient(ingredientJson.toString(),contentType, sourceStream)

        try {
            // Sign the image
            Timber.d( "Calling builder.sign()")
            builder.sign(
                format = contentType,
                source = sourceStream,
                dest = destStream,
                signer = signer,
            )


            Timber.d( "builder.sign() completed successfully")

        } catch (e: Exception) {
            Timber.e( e, "Error in signImageData")
            Timber.e( "Error message: ${e.message}")
            Timber.e( "Error cause: ${e.cause}")
            throw e
        } finally {
            // Make sure to close streams
            Timber.d( "Closing streams")
            sourceStream.close()
            destStream.close()
        }
    }

    public fun validateSignedMedia(filePath: String): Boolean {


            // Read and verify using C2PA
            val manifestJSON = C2PA.readFile(filePath, null)
            Timber.d( "Manifest JSON length: ${manifestJSON.length} characters")
            Timber.d("Menifest JSON:\n${manifestJSON}")

            val validation = ManifestValidator.validateJson(manifestJSON, logWarnings = true)
            if (validation.hasErrors()) {
                Timber.d( "C2PA VALIDATION ERRORS")

                Timber.d(validation.errors.joinToString("; "))

            }

            if (validation.isValid())
            {

                Timber.d( "C2PA MANIFEST IS VALID")
                return true

            }
            else
            {
                Timber.d( "C2PA MANIFEST IS INVALID")
                return false
            }





        return false
    }

    /**
     * Should be under c2pa.metadata
     *
     * {
     * 	"@context" : {
     * 		"exif": "http://ns.adobe.com/exif/1.0/",
     * 		"exifEX": "http://cipa.jp/exif/2.32/",
     * 		"tiff": "http://ns.adobe.com/tiff/1.0/",
     * 		"Iptc4xmpExt": "http://iptc.org/std/Iptc4xmpExt/2008-02-29/",
     * 		"photoshop" : "http://ns.adobe.com/photoshop/1.0/"
     * 	},
     * 	"photoshop:DateCreated": "Aug 31, 2022",
     * 	"Iptc4xmpExt:DigitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture",
     * 	"exif:GPSVersionID": "2.2.0.0",
     * 	"exif:GPSLatitude": "39,21.102N",
     * 	"exif:GPSLongitude": "74,26.5737W",
     * 	"exif:GPSAltitudeRef": 0,
     * 	"exif:GPSAltitude": "100963/29890",
     * 	"exif:GPSTimeStamp": "18:22:57",
     * 	"exif:GPSDateStamp": "2019:09:22",
     * 	"exif:GPSSpeedRef": "K",
     * 	"exif:GPSSpeed": "4009/161323",
     * 	"exif:GPSImgDirectionRef": "T",
     * 	"exif:GPSImgDirection": "296140/911",
     * 	"exif:GPSDestBearingRef": "T",
     * 	"exif:GPSDestBearing": "296140/911",
     * 	"exif:GPSHPositioningError": "13244/2207",
     * 	"exif:ExposureTime": "1/100",
     * 	"exif:FNumber": 4.0,
     * 	"exif:ColorSpace": 1,
     * 	"exif:DigitalZoomRatio": 2.0,
     * 	"tiff:Make": "CameraCompany",
     * 	"tiff:Model": "Shooter S1",
     * 	"exifEX:LensMake": "CameraCompany",
     * 	"exifEX:LensModel": "17.0-35.0 mm",
     * 	"exifEX:LensSpecification": { "@list": [ 1.55, 4.2, 1.6, 2.4 ] }
     *
     * }
     */
    private fun createExifAssertion(context: Context, location: Location?, fileIn: File, contentType: String): AssertionDefinition {

        // Custom assertion
        return AssertionDefinition.custom(
           // label = "c2pa.metadata",
            label = "stds.exif",
            data = buildJsonObject {

                put ("@context",
                    buildJsonObject {
                        put("exif", "http://ns.adobe.com/exif/1.0/")
                        put("dc", "http://purl.org/dc/elements/1.1/")
                        put("exifEX", "http://cipa.jp/exif/2.32/")
                        put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                        put("tiff", "http://ns.adobe.com/tiff/1.0/")
                        put("xmp", "http://ns.adobe.com/xap/1.0/")
                        put("Iptc4xmpExt", "http://iptc.org/std/Iptc4xmpExt/2008-02-29/")

                    })

                location?.let {
                    put("exif:GPSLatitude", it.latitude.toString())
                    put("exif:GPSLongitude", it.longitude.toString())
                    put("exif:GPSAltitude", it.altitude.toString())

                    put ("exif:GPSAccuracy", it.accuracy.toString())
                    put ("exif:GPSSpeed", it.speed.toString())
                    put ("exif:GPSDestBearing", it.bearing.toString())
                    put ("exif:GPSVersionID", "2.2.0.0")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        put ("exif:GPSProcessingMethod", "Provider=${location.provider};IsMock=${it.isMock}")
                    }
                    else
                    {
                        put ("exif:GPSProcessingMethod", "Provider=${location.provider}")
                    }

                    val timestamp = formatIsoTimestamp(Date(it.time))
                    put("exif:GPSTimeStamp", timestamp)
                }

                val exifMake = Build.MANUFACTURER
                val exifModel = Build.MODEL

                put ("tiff:Make",exifMake)
                put ("tiff:Model", exifModel)
                put ("tiff:DateTime", formatIsoTimestamp(Date(fileIn.lastModified())))
                put ("exifEX:LensMake", exifMake)
                put ("exifEX:LensModel", exifModel)


            })

    }

    private fun createCAWGAssertion (context: Context, blockAI: Boolean, creatorEmail: String?): AssertionDefinition {

        /**
         *  CawgTrainingMiningEntry(
         *     use: String,
         *     constraintInfo: String? = null,
         *     aiModelLearningType: String? = null,
         *     aiMiningType: String? = null
         * )
         */

        var cawgList = ArrayList<CawgTrainingMiningEntry>()

        if (blockAI) {
            cawgList.add(CawgTrainingMiningEntry("cawg.data_mining", "notAllowed"))
            cawgList.add(CawgTrainingMiningEntry("cawg.ai_generative_training", "notAllowed"))
            cawgList.add(CawgTrainingMiningEntry("cawg.ai_training", "notAllowed"))
            cawgList.add(CawgTrainingMiningEntry("cawg.ai_inference", "notAllowed"))
        }

        //var result = AssertionDefinition.cawgTrainingMining(cawgList)

        var result = AssertionDefinition.custom(
            label = "cawg.training-mining",
            data = buildJsonObject {

                put ("entries",
                    buildJsonObject {
                        for (cawgEntry in cawgList) {
                            put (cawgEntry.use,buildJsonObject {
                                put("use", cawgEntry.constraintInfo)
                            })
                        }
                    }

                )

            }
        )

        return result
    }

    private fun createProofmodeAssertion(context: Context, inFile: File): AssertionDefinition {

        var sigs = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures;

        val keyAlias = "attested_key"
        val keyGen = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )

        //generate a local hardware-back signature with the nonce of the sha256 ingredient image inside of it
        //https://proandroiddev.com/your-app-is-secure-but-is-the-device-android-hardware-attestation-explained-e9a531312035
        val nonceOfIngredient = HashUtils.getSHA256FromFileContent(FileInputStream(inFile))

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(nonceOfIngredient.toByteArray())        // request attestation
            .build()

        keyGen.initialize(spec)

        //create temp key with Nonce
        val keyPair = keyGen.generateKeyPair()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        val certChain = ks.getCertificateChain(keyAlias)

        // Custom assertion
        var result = AssertionDefinition.custom(
            label = "proofmode.metadata",
            data = buildJsonObject {

                put ("@context",
                    buildJsonObject {
                        put("proofmode", "http://proofmode.org/c2pa/spec/1.0/")
                    })

                put ("proofmode:UserPublicKey",ProofMode.getPublicKeyString())


                if (sigs != null) {
                    for ( sig in sigs)
                    {

                                var rawCert = sig.toByteArray();
                                var certStream = ByteArrayInputStream(rawCert);

                                try {
                                    var certFactory = CertificateFactory.getInstance("X509");
                                    var x509Cert =
                                        certFactory.generateCertificate(certStream) as X509Certificate;

                                    put ("proofmode:AppSignature-${x509Cert.serialNumber}",
                                        buildJsonObject {
                                            put(
                                                "proofmode:AppCertificateSubject",
                                                x509Cert.subjectDN.name
                                            );
                                            put(
                                                "proofmode:AppCertificateNotBefore",
                                                x509Cert.notBefore.toString()
                                            );
                                            put("proofmode:AppSignatures", sig.toCharsString())

                                        })
                                    } catch (e: CertificateException) {
                                    // e.printStackTrace();
                                }


                    }
                }

                if (certChain != null)
                {
                    for (cert in certChain)
                    {
                        var xCert = cert as X509Certificate
                        put("proofmode:HardwareAttestation-${xCert.serialNumber}", xCert.toString())
                    }
                }

            })

        //just for one moment!
        ks.deleteEntry(keyAlias)

        return result

    }

    private fun formatIsoTimestamp(date: Date): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(date)
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
        var applicationInfo: ApplicationInfo? = null
        try {
            applicationInfo = context.packageManager.getApplicationInfo(context.applicationInfo.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d("TAG", "The package with the given name cannot be found on the system.", e)
        }
        return (if (applicationInfo != null) context.packageManager.getApplicationLabel(applicationInfo) else "Unknown") as String

    }
}
