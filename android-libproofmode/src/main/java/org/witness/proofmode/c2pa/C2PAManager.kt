package org.witness.proofmode.c2pa

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.os.Build
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.WrappedKeyEntry
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.contentauth.c2pa.Builder
import org.contentauth.c2pa.ByteArrayStream
import org.contentauth.c2pa.C2PA
import org.contentauth.c2pa.CertificateManager
import org.contentauth.c2pa.DataStream
import org.contentauth.c2pa.FileStream
import org.contentauth.c2pa.KeyStoreSigner
import org.contentauth.c2pa.Signer
import org.contentauth.c2pa.SigningAlgorithm
import org.contentauth.c2pa.Stream
import org.contentauth.c2pa.StrongBoxSigner
import org.contentauth.c2pa.WebServiceSigner
import org.contentauth.c2pa.manifest.Action
import org.contentauth.c2pa.manifest.AttestationBuilder
import org.contentauth.c2pa.manifest.C2PAActions
import org.contentauth.c2pa.manifest.C2PAFormats
import org.contentauth.c2pa.manifest.C2PARelationships
import org.contentauth.c2pa.manifest.Ingredient
import org.contentauth.c2pa.manifest.ManifestBuilder
import org.contentauth.c2pa.manifest.TimestampAuthorities
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
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

        // Using null for TSA URL to skip timestamping for testing
        private const val DEFAULT_TSA_URL = "http://timestamp.digicert.com"

    }

    private val httpClient = OkHttpClient()

    private var defaultCertificate: String? = null
    private var defaultPrivateKey: String? = null

    init {
        loadDefaultCertificates()
    }

    private fun loadDefaultCertificates() {
        try {
            // Load default test certificates from assets
            context.assets.open("default_certs.pem").use { stream ->
                defaultCertificate = stream.bufferedReader().readText()
            }
            context.assets.open("default_private.key").use { stream ->
                defaultPrivateKey = stream.bufferedReader().readText()
            }
            Log.d(TAG, "Default certificates loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading default certificates", e)
        }
    }

    suspend fun signImage(bitmap: Bitmap, location: Location? = null): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            // Convert bitmap to JPEG bytes
            val imageBytes =
                ByteArrayOutputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    outputStream.toByteArray()
                }

            Log.d(TAG, "Original image size: ${imageBytes.size} bytes")

            // Get current signing mode
            val signingMode = preferencesManager.signingMode.first()
            Log.d(TAG, "Using signing mode: $signingMode")

            // Create manifest JSON
            val manifestJSON = createManifestJSON(context, "Android", "Image from Android", location, true, signingMode)

            // Create appropriate signer based on mode
            val signer = createSigner(signingMode)

            // Sign the image using C2PA library
            val signedBytes = signImageData(imageBytes, manifestJSON, signer)

            Log.d(TAG, "Signed image size: ${signedBytes.size} bytes")
            Log.d(TAG, "Size difference: ${signedBytes.size - imageBytes.size} bytes")

            // Verify the signed image
            verifySignedImage(signedBytes)

            Result.success(signedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error signing image", e)
            Result.failure(e)
        }
    }

    suspend fun signMediaFile(inFile: File, contentType: String, location: Location? = null, creator: String, outFile: File): Result<Stream> = withContext(Dispatchers.IO) {
        try {


            Log.d(TAG, "Original file size: ${inFile.length()} bytes")

            // Get current signing mode
            val signingMode = preferencesManager.signingMode.first()
            Log.d(TAG, "Using signing mode: $signingMode")

            // Create manifest JSON
            val manifestJSON = createManifestJSON(context, creator, inFile.name, location, true, signingMode)

            // Create appropriate signer based on mode
            val signer = createSigner(signingMode)

            // Sign the image using C2PA library
            //val signedBytes = signImageData(imageBytes, manifestJSON, signer)

            val fileStream = FileStream(inFile)
            val outStream = FileStream(outFile)
            signStream(fileStream, contentType, outStream, manifestJSON, signer)

            Log.d(TAG, "Signed file size: ${outFile.length()} bytes")

            // Verify the signed image
            //verifySignedImage(signedBytes)

            Result.success(outStream)
        } catch (e: Exception) {
            Log.e(TAG, "Error signing image", e)
            Result.failure(e)
        }
    }

    private suspend fun createSigner(mode: SigningMode): Signer = withContext(Dispatchers.IO) {
        when (mode) {
            SigningMode.DEFAULT -> createDefaultSigner()
            SigningMode.KEYSTORE -> createKeystoreSigner()
            SigningMode.HARDWARE -> createHardwareSigner()
            SigningMode.CUSTOM -> createCustomSigner()
            SigningMode.REMOTE -> createRemoteSigner()
        }
    }

    private fun createDefaultSigner(): Signer {
        requireNotNull(defaultCertificate) { "Default certificate not available" }
        requireNotNull(defaultPrivateKey) { "Default private key not available" }

        Log.d(TAG, "Creating default signer with test certificates")
        Log.d(TAG, "Certificate length: ${defaultCertificate!!.length} chars")
        Log.d(TAG, "Private key length: ${defaultPrivateKey!!.length} chars")
        Log.d(
            TAG,
            "TSA URL: ${if (DEFAULT_TSA_URL.isEmpty()) "NONE (skipping timestamping)" else DEFAULT_TSA_URL}",
        )

        return try {
            Signer.fromKeys(
                certsPEM = defaultCertificate!!,
                privateKeyPEM = defaultPrivateKey!!,
                algorithm = SigningAlgorithm.ES256,
                tsaURL = if (DEFAULT_TSA_URL.isEmpty()) null else DEFAULT_TSA_URL,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create default signer", e)
            throw e
        }
    }

    private suspend fun createKeystoreSigner(): Signer {
        val keyAlias = "C2PA_SOFTWARE_KEY_SECURE"
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Create or get the keystore key
        if (!keyStore.containsAlias(keyAlias)) {
            Log.d(TAG, "Creating new keystore key")
            createKeystoreKey(keyAlias, false)
        }

        // Get certificate chain from signing server
        val certChain = enrollHardwareKeyCertificate(keyAlias)

        Log.d(TAG, "Using KeyStoreSigner with keyAlias: $keyAlias")

        // Use the new KeyStoreSigner class
        return KeyStoreSigner.createSigner(
            algorithm = SigningAlgorithm.ES256,
            certificateChainPEM = certChain,
            keyAlias = keyAlias,
            tsaURL = if (DEFAULT_TSA_URL.isEmpty()) null else DEFAULT_TSA_URL,
        )
    }

    private suspend fun createHardwareSigner(): Signer {
        val alias =
            preferencesManager.hardwareKeyAlias.first()
                ?: "$KEYSTORE_ALIAS_PREFIX${SigningMode.HARDWARE.name}"

        // Get or create hardware-backed key
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(alias)) {
            Log.d(TAG, "Creating new hardware-backed key with StrongBox if available")

            // Create StrongBox config
            val config = StrongBoxSigner.Config(keyTag = alias, requireUserAuthentication = false)

            // Create key using StrongBoxSigner (will use StrongBox if available, TEE otherwise)
            try {
                StrongBoxSigner.createKey(config)
                preferencesManager.setHardwareKeyAlias(alias)
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox key creation failed, falling back to hardware-backed key")
                createKeystoreKey(alias, true)
                preferencesManager.setHardwareKeyAlias(alias)
            }
        }

        // Get certificate chain from signing server
        val certChain = enrollHardwareKeyCertificate(alias)

        Log.d(TAG, "Creating StrongBoxSigner")

        // Create StrongBox config
        val config = StrongBoxSigner.Config(keyTag = alias, requireUserAuthentication = false)

        // Use the new StrongBoxSigner class
        return StrongBoxSigner.createSigner(
            algorithm = SigningAlgorithm.ES256,
            certificateChainPEM = certChain,
            config = config,
            tsaURL = if (DEFAULT_TSA_URL.isEmpty()) null else DEFAULT_TSA_URL,
        )
    }

    private suspend fun createCustomSigner(): Signer {
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
            Log.d(TAG, "Importing custom private key into Android Keystore")

            // Remove old key if exists
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }

            // Try to import, fallback to direct key usage if it fails
            try {
                importKeySecurely(keyAlias, keyPEM)
                Log.d(TAG, "Successfully imported custom key using Secure Key Import")
                preferencesManager.setCustomKeyHash(currentKeyHash)
            } catch (e: Exception) {
                Log.w(TAG, "Custom key import failed, using direct key: ${e.message}")
                // Fallback to direct key usage
                return Signer.fromKeys(
                    certsPEM = certPEM,
                    privateKeyPEM = keyPEM,
                    algorithm = SigningAlgorithm.ES256,
                    tsaURL = if (DEFAULT_TSA_URL.isEmpty()) null else DEFAULT_TSA_URL,
                )
            }
        }

        Log.d(TAG, "Creating custom signer with KeyStoreSigner")

        // Use the new KeyStoreSigner class
        return KeyStoreSigner.createSigner(
            algorithm = SigningAlgorithm.ES256,
            certificateChainPEM = certPEM,
            keyAlias = keyAlias,
            tsaURL = if (DEFAULT_TSA_URL.isEmpty()) null else DEFAULT_TSA_URL,
        )
    }

    private suspend fun createRemoteSigner(): Signer {
        val remoteUrl =
            preferencesManager.remoteUrl.first()
                ?: throw IllegalStateException("Remote signing URL not configured")
        val bearerToken = preferencesManager.remoteToken.first()

        val configUrl =
            if (remoteUrl.contains("/api/v1/c2pa/configuration")) {
                remoteUrl
            } else {
                "$remoteUrl/api/v1/c2pa/configuration"
            }

        Log.d(TAG, "Creating WebServiceSigner with URL: $configUrl")

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
        val remoteUrl =
            preferencesManager.remoteUrl.first()
                ?: throw IllegalStateException("Remote URL required for hardware signing")
        val bearerToken = preferencesManager.remoteToken.first()

        // Generate CSR for the hardware key
        val csr = generateCSR(alias)

        // Submit CSR to signing server
        val enrollUrl = "$remoteUrl/api/v1/certificates/sign"

        val requestBody = JSONObject().apply { put("csr", csr) }.toString()

        val request =
            Request.Builder()
                .url(enrollUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .apply {
                    if (!bearerToken.isNullOrEmpty()) {
                        addHeader("Authorization", "Bearer $bearerToken")
                    }
                }
                .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Certificate enrollment failed: ${response.code}")
        }

        val responseJson = JSONObject(response.body?.string())
        val certChain = responseJson.getString("certificate_chain")
        val certId = responseJson.getString("certificate_id")

        Log.d(TAG, "Certificate enrolled successfully. ID: $certId")

        return certChain
    }

    private fun generateCSR(alias: String): String {
        try {
            // Use the library's CertificateManager to generate a proper CSR
            val config =
                CertificateManager.CertificateConfig(
                    commonName = "C2PA Hardware Key",
                    organization = "C2PA Example App",
                    organizationalUnit = "Mobile",
                    country = "US",
                    state = "CA",
                    locality = "San Francisco",
                )

            // Generate CSR using the library
            val csr = CertificateManager.createCSR(alias, config)

            Log.d(TAG, "Generated proper CSR for alias $alias")
            return csr
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate CSR", e)
            throw RuntimeException("Failed to generate CSR: ${e.message}", e)
        }
    }

    /** Import key using Secure Key Import (API 28+) Throws exception if import fails */
    private fun importKeySecurely(keyAlias: String, privateKeyPEM: String) {
        try {
            Log.d(TAG, "Starting key import for alias: $keyAlias")
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            // Parse the private key from PEM
            val privateKeyBytes = parsePrivateKeyFromPEM(privateKeyPEM)
            val keyFactory = KeyFactory.getInstance("EC")
            val privateKey =
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as
                    ECPrivateKey

            Log.d(TAG, "Private key parsed, algorithm: ${privateKey.algorithm}")

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
            Log.d(TAG, "Wrapping key generated")

            // Get the public key for wrapping
            val publicKey = wrappingKeyPair.public

            // Wrap the private key
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(Cipher.WRAP_MODE, publicKey)
            val wrappedKeyBytes = cipher.wrap(privateKey)
            Log.d(TAG, "Key wrapped, bytes length: ${wrappedKeyBytes.size}")

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
            Log.d(TAG, "Key imported to keystore")

            // Clean up wrapping key
            keyStore.deleteEntry(wrappingKeyAlias)

            // Verify import
            if (keyStore.containsAlias(keyAlias)) {
                Log.d(TAG, "Key successfully imported and verified in keystore")
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

    private fun signImageData(imageData: ByteArray, manifestJSON: String, signer: Signer): ByteArray {
        Log.d(TAG, "Starting signImageData")
        Log.d(TAG, "Input image size: ${imageData.size} bytes")
        Log.d(TAG, "Manifest JSON: ${manifestJSON.take(200)}...") // First 200 chars

        // Create Builder with manifest
        Log.d(TAG, "Creating Builder from JSON")
        val builder = Builder.fromJson(manifestJSON)

        // Use ByteArrayStream which is designed for this purpose
        Log.d(TAG, "Creating streams")
        val sourceStream = DataStream(imageData)
        val destStream = ByteArrayStream()

        try {
            // Sign the image
            Log.d(TAG, "Calling builder.sign()")
            builder.sign(
                format = "image/jpeg",
                source = sourceStream,
                dest = destStream,
                signer = signer,
            )

            Log.d(TAG, "builder.sign() completed successfully")
            val result = destStream.getData()
            Log.d(TAG, "Output size: ${result.size} bytes")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error in signImageData", e)
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Error cause: ${e.cause}")
            throw e
        } finally {
            // Make sure to close streams
            Log.d(TAG, "Closing streams")
            sourceStream.close()
            destStream.close()
        }
    }

    private fun signStream(sourceStream: Stream, contentType: String, destStream: Stream, manifestJSON: String, signer: Signer) {
        Log.d(TAG, "Starting signImageData")
        Log.d(TAG, "Manifest JSON: ${manifestJSON.take(200)}...") // First 200 chars

        // Create Builder with manifest
        Log.d(TAG, "Creating Builder from JSON")
        val builder = Builder.fromJson(manifestJSON)

        try {
            // Sign the image
            Log.d(TAG, "Calling builder.sign()")
            builder.sign(
                format = contentType,
                source = sourceStream,
                dest = destStream,
                signer = signer,
            )

            Log.d(TAG, "builder.sign() completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error in signImageData", e)
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Error cause: ${e.cause}")
            throw e
        } finally {
            // Make sure to close streams
            Log.d(TAG, "Closing streams")
            sourceStream.close()
            destStream.close()
        }
    }

    private fun createManifestJSON(context: Context, creator: String, fileName: String, location: Location?, isDirectCapture: Boolean, signingMode: SigningMode): String {

        val appLabel = getAppName(context)
        val appVersion = getAppVersionName(context)

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
        val thumbnailId = fileName + "-thumb.jpg"

        var mb = ManifestBuilder()
        mb.claimGenerator(appLabel, version = appVersion)
        mb.timestampAuthorityUrl(TimestampAuthorities.DIGICERT)

        mb.title(fileName)
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
            title = fileName,
            format = C2PAFormats.JPEG,
            relationship = C2PARelationships.PARENT_OF,
            //         thumbnail = Thumbnail(C2PAFormats.JPEG, thumbnailId)
        )

        mb.addIngredient(ingredient)

        val attestationBuilder = AttestationBuilder()

        attestationBuilder.addCreativeWork {
            addAuthor(creator)
            dateCreated(Date())
        }

        val gpsTracker = GPSTracker(context)
        if (location  != null && gpsTracker.canGetLocation()) {

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
                    location(locationJson)
                }
            }


        }



        /**
        val customAttestationJson = JSONObject().apply {
        put("@type", "Integrity")
        put("nonce", "something")
        put("response", "b64encodedresponse")
        }

        attestationBuilder.addCustomAttestation("app.integrity", customAttestationJson)

        attestationBuilder.addCAWGIdentity {
            validFromNow()
            addSocialMediaIdentity(pgpFingerprint, webLink, currentTs, appLabel, appLabel)
        }
         **/



        attestationBuilder.buildForManifest(mb)

        val manifestJson = mb.buildJson()


        return manifestJson
    }

    private fun verifySignedImage(imageData: ByteArray) {
        try {
            // Create a temporary file for verification
            val tempFile = File.createTempFile("verify", ".jpg", context.cacheDir)
            tempFile.writeBytes(imageData)

            // Read and verify using C2PA
            val manifestJSON = C2PA.readFile(tempFile.absolutePath, null)

            Log.d(TAG, "C2PA VERIFICATION SUCCESS")
            Log.d(TAG, "Manifest JSON length: ${manifestJSON.length} characters")

            // Parse and log key information
            val manifest = JSONObject(manifestJSON)
            manifest.optJSONObject("active_manifest").let { activeManifest ->
                Log.d(TAG, "Active manifest found")
                activeManifest?.optString("claim_generator").let {
                    Log.d(TAG, "Claim generator: $it")
                }
                activeManifest?.optString("title")?.let { Log.d(TAG, "Title: $it") }
                activeManifest?.optJSONObject("signature_info")?.let { sigInfo ->
                    Log.d(TAG, "Signature info present")
                    sigInfo.optString("alg").let { Log.d(TAG, "Algorithm: $it") }
                    sigInfo.optString("issuer").let { Log.d(TAG, "Issuer: $it") }
                }
            }

            // Clean up temp file
            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "C2PA VERIFICATION FAILED", e)
        }
    }

    private fun createLocationAssertion(location: Location): JSONObject {
        val timestamp = formatIsoTimestamp(Date(location.time))
        val metadata =
            JSONObject().apply {
                put("exif:GPSLatitude", location.latitude.toString())
                put("exif:GPSLongitude", location.longitude.toString())
                put("exif:GPSAltitude", location.altitude.toString())
                put("exif:GPSTimeStamp", timestamp)
                put(
                    "@context",
                    JSONObject().apply { put("exif", "http://ns.adobe.com/exif/1.0/") },
                )
            }
        return JSONObject().apply {
            put("label", "c2pa.metadata")
            put("data", metadata)
        }
    }

    private fun createCreationAssertion(): JSONObject {
        val timestamp = formatIsoTimestamp(Date())
        val action =
            JSONObject().apply {
                put("action", "c2pa.created")
                put("when", timestamp)
            }
        val actions = JSONArray().apply { put(action) }
        val data = JSONObject().apply { put("actions", actions) }
        return JSONObject().apply {
            put("label", "c2pa.actions")
            put("data", data)
        }
    }

    private fun formatIsoTimestamp(date: Date): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(date)
    }

    fun saveImageToGallery(imageData: ByteArray): Result<String> = try {
        // Implementation depends on Android version
        // For simplicity, saving to app's external files directory
        val photosDir =
            File(
                context.getExternalFilesDir(
                    Environment.DIRECTORY_PICTURES,
                ),
                "C2PA",
            )
        Log.d(TAG, "Gallery directory: ${photosDir.absolutePath}")
        Log.d(TAG, "Directory exists: ${photosDir.exists()}")

        if (!photosDir.exists()) {
            val created = photosDir.mkdirs()
            Log.d(TAG, "Directory created: $created")
        }

        val fileName = "C2PA_${System.currentTimeMillis()}.jpg"
        val file = File(photosDir, fileName)
        file.writeBytes(imageData)

        Log.d(TAG, "Image saved to: ${file.absolutePath}")
        Log.d(TAG, "File exists: ${file.exists()}")
        Log.d(TAG, "File size: ${file.length()} bytes")

        // Verify the file can be read back
        if (file.exists() && file.canRead()) {
            Log.d(TAG, "File successfully saved and readable")
        } else {
            Log.e(TAG, "File saved but cannot be read")
        }

        Result.success(file.absolutePath)
    } catch (e: Exception) {
        Log.e(TAG, "Error saving image", e)
        Result.failure(e)
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
}
