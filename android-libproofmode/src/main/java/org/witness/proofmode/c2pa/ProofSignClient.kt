package org.witness.proofmode.c2pa

/**
 * ProofSign Android Client Single-file client for Google Play Integrity device verification with
 * ProofSign server
 *
 * Setup:
 * 1. Add to build.gradle.kts:
 * ```
 *    implementation("com.google.android.play:integrity:1.3.0")
 *    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
 *    implementation("com.squareup.okhttp3:okhttp:4.12.0")
 * ```
 * 2. Add to AndroidManifest.xml:
 * ```
 *    <uses-permission android:name="android.permission.INTERNET" />
 * ```
 * 3. Configure Play Integrity in Google Cloud Console:
 * ```
 *    - Enable Play Integrity API
 *    - Link your app to the project
 * ```
 * Usage:
 * ```
 *    val client = ProofSignClient(
 *        context = applicationContext,
 *        serverUrl = "https://your-server.com",
 *        cloudProjectNumber = "YOUR_PROJECT_NUMBER"
 *    )
 *
 *    // Initial verification (do this once or periodically)
 *    client.verifyDevice { result ->
 *        when (result) {
 *            is Result.Success -> println("Verified: ${result.data.deviceId}")
 *            is Result.Failure -> println("Error: ${result.error}")
 *        }
 *    }
 *
 *    // Make authenticated API calls
 *    client.authenticatedRequest(
 *        endpoint = "/api/v1/protected-endpoint",
 *        method = "POST",
 *        body = """{"data": "value"}"""
 *    ) { result ->
 *        when (result) {
 *            is Result.Success -> println("Response: ${result.data}")
 *            is Result.Failure -> println("Error: ${result.error}")
 *        }
 *    }
 * ```
 */

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure(val error: String, val exception: Exception? = null) : Result<Nothing>()
}

enum class AuthMethod {
    BEARER_TOKEN,
    DEVICE_ATTESTATION
}

data class VerificationResult(val deviceId: String, val verdict: String, val expiresAt: String)

class ProofSignClient(
    private val context: Context,
    private val serverUrl: String,
    private val cloudProjectNumber: String,
    bearerToken: String? = null
) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("proofsign_prefs", Context.MODE_PRIVATE)

    private val integrityManager: IntegrityManager = IntegrityManagerFactory.create(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ProofSignClient"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_LAST_VERIFICATION = "last_verification"
        private const val PREF_VERIFICATION_EXPIRES = "verification_expires"
        private const val PREF_BEARER_TOKEN = "bearer_token"
        private const val PREF_ASSERTION_COUNTER = "assertion_counter"
        private const val VERIFICATION_VALIDITY_DAYS = 7
        private const val KEYSTORE_ALIAS = "proofsign_device_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    var bearerToken: String?
        get() = prefs.getString(PREF_BEARER_TOKEN, null)
        set(value) {
            if (value != null) {
                prefs.edit().putString(PREF_BEARER_TOKEN, value).apply()
                Log.d(TAG, "Bearer token stored")
            } else {
                prefs.edit().remove(PREF_BEARER_TOKEN).apply()
                Log.d(TAG, "Bearer token cleared")
            }
        }

    init {
        if (bearerToken != null) {
            this.bearerToken = bearerToken
        }
    }

    /** Get or create a stable device identifier */
    private fun getDeviceId(): String {
        var deviceId = prefs.getString(PREF_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(PREF_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    /** Check if device verification is still valid */
    fun isVerificationValid(): Boolean {
        val expiresAt = prefs.getLong(PREF_VERIFICATION_EXPIRES, 0)
        return System.currentTimeMillis() < expiresAt
    }

    /** Get or create an EC P-256 keypair in Android Keystore for request signing */
    private fun getOrCreateKeyPair(): java.security.KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Check if key already exists
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
            val publicKey = keyStore.getCertificate(KEYSTORE_ALIAS).publicKey
            Log.d(TAG, "Using existing keypair from Android Keystore")
            return java.security.KeyPair(publicKey, privateKey)
        }

        // Generate new keypair
        Log.d(TAG, "Generating new EC P-256 keypair in Android Keystore")
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .build()

        keyPairGenerator.initialize(parameterSpec)
        return keyPairGenerator.generateKeyPair()
    }

    /** Get the public key as base64-encoded DER (SubjectPublicKeyInfo) */
    private fun getPublicKeyBase64(): String {
        val keyPair = getOrCreateKeyPair()
        val publicKeyBytes = keyPair.public.encoded
        return Base64.getEncoder().encodeToString(publicKeyBytes)
    }

    /** Sign data with the device's private key using ECDSA with SHA-256 */
    private fun signWithDeviceKey(data: ByteArray): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)

        val signatureBytes = signature.sign()
        return Base64.getEncoder().encodeToString(signatureBytes)
    }

    /** Get and increment the assertion counter for replay protection */
    private fun getAndIncrementCounter(): Long {
        val counter = prefs.getLong(PREF_ASSERTION_COUNTER, 0)
        prefs.edit().putLong(PREF_ASSERTION_COUNTER, counter + 1).apply()
        return counter
    }

    /** Generate a nonce for Play Integrity request (base64 URL-safe, no padding) */
    private fun generateNonce(deviceId: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val random = UUID.randomUUID().toString()
        val combined = "$deviceId:$timestamp:$random"

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        // Use URL-safe base64 encoding without padding (required by Play Integrity API)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    /** Request Play Integrity token from Google */
    private fun requestIntegrityToken(nonce: String, callback: (Result<String>) -> Unit) {
        val integrityTokenRequest =
            IntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber.toLong())
                .setNonce(nonce)
                .build()

        integrityManager
            .requestIntegrityToken(integrityTokenRequest)
            .addOnSuccessListener { response: IntegrityTokenResponse ->
                val token = response.token()
                Log.d(TAG, "Integrity token obtained: ${token.take(50)}...")
                callback(Result.Success(token))
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to get integrity token", exception)
                callback(
                    Result.Failure(
                        "Failed to get integrity token: ${exception.message}",
                        exception as? Exception
                    )
                )
            }
    }

    /** Verify device with ProofSign server */
    fun verifyDevice(callback: (Result<VerificationResult>) -> Unit) {
        scope.launch {
            try {
                val deviceId = getDeviceId()
                val nonce = generateNonce(deviceId)

                Log.d(TAG, "Starting device verification for device: $deviceId")

                // Request integrity token from Google Play
                requestIntegrityToken(nonce) { tokenResult ->
                    when (tokenResult) {
                        is Result.Success -> {
                            scope.launch {
                                verifyWithServer(
                                    deviceId = deviceId,
                                    token = tokenResult.data,
                                    nonce = nonce,
                                    callback = callback
                                )
                            }
                        }

                        is Result.Failure -> {
                            scope.launch {
                                withContext(Dispatchers.Main) { callback(tokenResult) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during device verification", e)
                withContext(Dispatchers.Main) {
                    callback(Result.Failure("Verification failed: ${e.message}", e))
                }
            }
        }
    }

    /** Send verification request to ProofSign server */
    private suspend fun verifyWithServer(
        deviceId: String,
        token: String,
        nonce: String,
        callback: (Result<VerificationResult>) -> Unit
    ) {
        try {
            val packageName = context.packageName
            // Generate keypair and get public key for registration
            val publicKey = getPublicKeyBase64()
            Log.d(TAG, "Registering public key with server (${publicKey.length} chars)")

            val json =
                JSONObject().apply {
                    put("device_id", deviceId)
                    put("integrity_token", token)
                    put("nonce", nonce)
                    put("package_name", packageName)
                    put("public_key", publicKey)
                }

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())

            val request =
                Request.Builder()
                    .url("$serverUrl/api/v1/play_integrity/verify")
                    .post(requestBody)
                    .build()

            Log.d(TAG, "Sending verification request to server")

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val responseJson = JSONObject(responseBody)
                    Log.d(TAG, "Verification response: $responseBody")

                    // Extract verdict from device_integrity and app_integrity
                    val deviceIntegrity = responseJson.optJSONObject("device_integrity")
                    val appIntegrity = responseJson.optJSONObject("app_integrity")

                    val deviceVerdict = deviceIntegrity
                        ?.optJSONArray("deviceRecognitionVerdict")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.joinToString(", ") }
                        ?: "UNKNOWN"
                    val appVerdict = appIntegrity?.optString("appRecognitionVerdict", "UNKNOWN") ?: "UNKNOWN"

                    val verdict = "Device: $deviceVerdict, App: $appVerdict"
                    Log.d(TAG, "Parsed verdict: $verdict")
                    val expiresAt = responseJson.optString("expires_at", "")

                    // Save verification state
                    val expirationTime =
                        System.currentTimeMillis() +
                                (VERIFICATION_VALIDITY_DAYS * 24 * 60 * 60 * 1000)
                    prefs.edit()
                        .putLong(PREF_LAST_VERIFICATION, System.currentTimeMillis())
                        .putLong(PREF_VERIFICATION_EXPIRES, expirationTime)
                        .apply()

                    Log.d(TAG, "Device verification successful: $verdict")

                    val result =
                        VerificationResult(
                            deviceId = deviceId,
                            verdict = verdict,
                            expiresAt = expiresAt
                        )

                    withContext(Dispatchers.Main) { callback(Result.Success(result)) }
                } else {
                    val errorMsg =
                        try {
                            val errorJson = JSONObject(responseBody)
                            errorJson.optString("message", "Unknown error")
                        } catch (e: Exception) {
                            "Server returned ${response.code}: $responseBody"
                        }

                    Log.e(TAG, "Verification failed: $errorMsg")

                    withContext(Dispatchers.Main) {
                        callback(Result.Failure("Verification failed: $errorMsg"))
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during verification", e)
            withContext(Dispatchers.Main) {
                callback(Result.Failure("Network error: ${e.message}", e))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during server verification", e)
            withContext(Dispatchers.Main) {
                callback(Result.Failure("Server verification failed: ${e.message}", e))
            }
        }
    }

    /** Make an authenticated API request using device verification */
    fun authenticatedRequest(
        endpoint: String,
        method: String = "GET",
        body: String? = null,
        callback: (Result<String>) -> Unit
    ) {
        scope.launch {
            try {
                if (!isVerificationValid()) {
                    withContext(Dispatchers.Main) {
                        callback(
                            Result.Failure(
                                "Device verification expired. Please call verifyDevice() first."
                            )
                        )
                    }
                    return@launch
                }

                val deviceId = getDeviceId()

                // Create request body with device auth info
                val requestJson =
                    JSONObject().apply {
                        put("platform", "android")
                        put("token", deviceId)

                        // Merge with provided body if present
                        if (body != null) {
                            try {
                                val bodyJson = JSONObject(body)
                                bodyJson.keys().forEach { key -> put(key, bodyJson.get(key)) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not parse body as JSON, using as-is")
                            }
                        }
                    }

                val requestBody =
                    requestJson.toString().toRequestBody("application/json".toMediaType())

                val request =
                    Request.Builder()
                        .url("$serverUrl$endpoint")
                        .apply {
                            when (method.uppercase()) {
                                "GET" -> get()
                                "POST" -> post(requestBody)
                                "PUT" -> put(requestBody)
                                "DELETE" -> delete(requestBody)
                                else ->
                                    throw IllegalArgumentException(
                                        "Unsupported method: $method"
                                    )
                            }
                        }
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-Device-Platform", "android")
                        .build()

                Log.d(TAG, "Making authenticated request to $endpoint")

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        Log.d(TAG, "Authenticated request successful")
                        withContext(Dispatchers.Main) { callback(Result.Success(responseBody)) }
                    } else {
                        val errorMsg =
                            try {
                                val errorJson = JSONObject(responseBody)
                                errorJson.optString("message", "Unknown error")
                            } catch (e: Exception) {
                                "Request failed with ${response.code}: $responseBody"
                            }

                        Log.e(TAG, "Authenticated request failed: $errorMsg")

                        withContext(Dispatchers.Main) { callback(Result.Failure(errorMsg)) }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error during authenticated request", e)
                withContext(Dispatchers.Main) {
                    callback(Result.Failure("Network error: ${e.message}", e))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during authenticated request", e)
                withContext(Dispatchers.Main) {
                    callback(Result.Failure("Request failed: ${e.message}", e))
                }
            }
        }
    }

    /** Clear stored verification data and bearer token */
    fun clearVerification() {
        prefs.edit()
            .remove(PREF_LAST_VERIFICATION)
            .remove(PREF_VERIFICATION_EXPIRES)
            .remove(PREF_BEARER_TOKEN)
            .apply()
        Log.d(TAG, "Verification data and bearer token cleared")
    }

    /** Get all Play Integrity verifications for this device */
    fun getVerificationHistory(callback: (Result<List<VerificationHistory>>) -> Unit) {
        scope.launch {
            try {
                val deviceId = getDeviceId()
                val request =
                    Request.Builder()
                        .url("$serverUrl/api/v1/play_integrity/verifications/$deviceId")
                        .get()
                        .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val jsonArray = org.json.JSONArray(responseBody)
                        val history = mutableListOf<VerificationHistory>()

                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.getJSONObject(i)
                            history.add(
                                VerificationHistory(
                                    id = item.getInt("id"),
                                    deviceId = item.getString("device_id"),
                                    verdict = item.getString("verdict"),
                                    createdAt = item.getString("verification_timestamp")
                                )
                            )
                        }

                        withContext(Dispatchers.Main) { callback(Result.Success(history)) }
                    } else {
                        withContext(Dispatchers.Main) {
                            callback(Result.Failure("Failed to get history: ${response.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback(Result.Failure("Error: ${e.message}", e)) }
            }
        }
    }

    /** Sign a C2PA hash using device authentication */
    fun signC2PAHash(hash: String, callback: (Result<C2PASignature>) -> Unit) {
        scope.launch {
            try {
                if (!isVerificationValid()) {
                    withContext(Dispatchers.Main) {
                        callback(Result.Failure("Device verification expired"))
                    }
                    return@launch
                }

                val deviceId = getDeviceId()
                val json =
                    JSONObject().apply {
                        put("hash", hash)
                        put("platform", "android")
                        put("token", deviceId)
                    }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                val request =
                    Request.Builder()
                        .url("$serverUrl/api/v1/sign_c2pa")
                        .post(requestBody)
                        .addHeader("X-Device-Platform", "android")
                        .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val responseJson = JSONObject(responseBody)
                        val signature =
                            C2PASignature(
                                signature = responseJson.getString("signature"),
                                certificate = responseJson.getString("certificate")
                            )

                        withContext(Dispatchers.Main) { callback(Result.Success(signature)) }
                    } else {
                        withContext(Dispatchers.Main) {
                            callback(Result.Failure("Signing failed: ${response.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback(Result.Failure("Error: ${e.message}", e)) }
            }
        }
    }

    /**
     * Sign a C2PA claim using device attestation authentication
     * Requires device to be verified first via verifyDevice()
     *
     * This method cryptographically signs the request using the device's private key
     * stored in Android Keystore, providing iOS-equivalent security where each request
     * is cryptographically bound to the verified device.
     *
     * If the server randomly challenges with a 428 response (approximately 5% of requests),
     * this method will automatically perform a fresh Play Integrity verification and retry.
     *
     * @param claim Base64-encoded claim data to sign
     * @param callback Result callback with C2PABearerSignature or error
     */
    fun signC2PAClaimWithDeviceAuth(claim: String, callback: (Result<C2PABearerSignature>) -> Unit) {
        signC2PAClaimWithDeviceAuthInternal(claim, isRetry = false, callback = callback)
    }

    private fun signC2PAClaimWithDeviceAuthInternal(
        claim: String,
        isRetry: Boolean,
        callback: (Result<C2PABearerSignature>) -> Unit
    ) {
        scope.launch {
            try {
                if (!isVerificationValid()) {
                    withContext(Dispatchers.Main) {
                        callback(Result.Failure("Device verification expired. Please call verifyDevice() first"))
                    }
                    return@launch
                }

                val deviceId = getDeviceId()
                val counter = getAndIncrementCounter()
                val timestamp = System.currentTimeMillis()

                Log.d(TAG, "Signing C2PA claim with device auth, device ID: $deviceId, counter: $counter")

                // Create the data to sign: deviceId|counter|timestamp|claim
                // This binds the request to this device and prevents replay attacks
                val dataToSign = "$deviceId|$counter|$timestamp|$claim"
                val requestSignature = signWithDeviceKey(dataToSign.toByteArray())

                Log.d(TAG, "Request signed with device key")

                val json = JSONObject().apply {
                    put("claim", claim)
                    put("platform", "android")
                    put("token", deviceId)
                    put("counter", counter)
                    put("timestamp", timestamp)
                    put("request_signature", requestSignature)
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                val request =
                    Request.Builder()
                        .url("$serverUrl/api/v1/c2pa/sign")
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-Device-Platform", "android")
                        .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    when {
                        response.isSuccessful -> {
                            val responseJson = JSONObject(responseBody)
                            val signature =
                                C2PABearerSignature(
                                    signature = responseJson.getString("signature")
                                )

                            Log.d(TAG, "Claim signed successfully with device auth")
                            withContext(Dispatchers.Main) { callback(Result.Success(signature)) }
                        }

                        response.code == 428 && !isRetry -> {
                            // Server requires fresh Play Integrity verification
                            Log.i(TAG, "Server requested fresh integrity verification (random challenge)")

                            // Parse challenge from response
                            val challengeJson = try {
                                JSONObject(responseBody)
                            } catch (e: Exception) {
                                null
                            }
                            val serverChallenge = challengeJson?.optString("challenge")

                            Log.d(TAG, "Performing fresh Play Integrity verification")

                            // Re-verify device with fresh Play Integrity token
                            verifyDevice { verifyResult ->
                                when (verifyResult) {
                                    is Result.Success -> {
                                        Log.d(TAG, "Fresh verification successful, retrying signing request")
                                        // Retry the signing request
                                        signC2PAClaimWithDeviceAuthInternal(claim, isRetry = true, callback = callback)
                                    }

                                    is Result.Failure -> {
                                        Log.e(TAG, "Fresh verification failed: ${verifyResult.error}")
                                        scope.launch {
                                            withContext(Dispatchers.Main) {
                                                callback(Result.Failure("Integrity re-verification failed: ${verifyResult.error}"))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        response.code == 428 && isRetry -> {
                            // Already retried once, don't loop forever
                            Log.e(TAG, "Integrity challenge failed even after re-verification")
                            withContext(Dispatchers.Main) {
                                callback(Result.Failure("Device integrity verification failed"))
                            }
                        }

                        else -> {
                            val errorMsg =
                                try {
                                    val errorJson = JSONObject(responseBody)
                                    errorJson.optString("message", "Unknown error")
                                } catch (e: Exception) {
                                    "Signing failed with ${response.code}: $responseBody"
                                }

                            Log.e(TAG, "Device auth signing failed: $errorMsg")
                            withContext(Dispatchers.Main) { callback(Result.Failure(errorMsg)) }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error during device auth signing", e)
                withContext(Dispatchers.Main) {
                    callback(Result.Failure("Network error: ${e.message}", e))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during device auth signing", e)
                withContext(Dispatchers.Main) {
                    callback(Result.Failure("Signing failed: ${e.message}", e))
                }
            }
        }
    }

    /**
     * Sign a C2PA claim using bearer token authentication
     * Requires bearerToken to be set
     *
     * @param claim Base64-encoded claim data to sign
     * @param callback Result callback with C2PABearerSignature or error
     */
    fun signC2PAClaimWithBearer(claim: String, callback: (Result<C2PABearerSignature>) -> Unit) {
        scope.launch {
            try {
                val token = bearerToken
                if (token == null) {
                    withContext(Dispatchers.Main) {
                        callback(Result.Failure("No bearer token configured. Set bearerToken property first"))
                    }
                    return@launch
                }

                Log.d(TAG, "Signing C2PA claim with bearer token")

                val json = JSONObject().apply {
                    put("claim", claim)
                    put("platform", "android")
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                val request =
                    Request.Builder()
                        .url("$serverUrl/api/v1/c2pa/sign")
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Bearer $token")
                        .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val responseJson = JSONObject(responseBody)
                        val signature =
                            C2PABearerSignature(
                                signature = responseJson.getString("signature")
                            )

                        Log.d(TAG, "Claim signed successfully with bearer token")
                        withContext(Dispatchers.Main) { callback(Result.Success(signature)) }
                    } else {
                        val errorMsg =
                            try {
                                val errorJson = JSONObject(responseBody)
                                errorJson.optString("message", "Unknown error")
                            } catch (e: Exception) {
                                "Signing failed with ${response.code}: $responseBody"
                            }

                        Log.e(TAG, "Bearer signing failed: $errorMsg")
                        withContext(Dispatchers.Main) { callback(Result.Failure(errorMsg)) }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error during bearer signing", e)
                withContext(Dispatchers.Main) {
                    callback(Result.Failure("Network error: ${e.message}", e))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during bearer signing", e)
                withContext(Dispatchers.Main) {
                    callback(Result.Failure("Signing failed: ${e.message}", e))
                }
            }
        }
    }

    /** Clean up resources */
    fun cleanup() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}

data class VerificationHistory(
    val id: Int,
    val deviceId: String,
    val verdict: String,
    val createdAt: String
)

data class C2PASignature(val signature: String, val certificate: String)

data class C2PABearerSignature(val signature: String)
