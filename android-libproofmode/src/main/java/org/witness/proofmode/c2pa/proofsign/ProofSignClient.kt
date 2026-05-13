package org.witness.proofmode.c2pa.proofsign

/**
 * ProofSign Android Client.
 *
 * Two device-auth registration paths, both backed by an EC P-256 keypair held in
 * the Android Keystore under `proofsign_device_key`. Once registered, per-request
 * signing is identical: each /api/v1/c2pa/sign call carries a signature over
 * "deviceId|counter|timestamp|claim" using the device key.
 *
 *  - Play Integrity (default): registers the device key via Google Play
 *    Integrity. Requires the Play Store on device.
 *
 *  - Key Attestation (fallback / forced): registers the device key by
 *    presenting an Android hardware key attestation cert chain. Used when
 *    Play Integrity is unavailable (e.g. de-Googled devices) or when the
 *    caller passes mode = AttestationMode.KEY_ATTESTATION.
 */

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure(val error: String, val exception: Exception? = null) : Result<Nothing>()
}

enum class AttestationMode {
    /** Decide automatically: Play Integrity if available, otherwise Key Attestation. */
    AUTO,
    /** Force the Key Attestation path even when Play Integrity is available (for testing). */
    KEY_ATTESTATION,
}

data class VerificationResult(val deviceId: String, val verdict: String)

class ProofSignClient(
    private val context: Context,
    private val serverUrl: String,
    private val cloudProjectNumber: String,
    private val mode: AttestationMode = AttestationMode.AUTO,
) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("proofsign_prefs", Context.MODE_PRIVATE)

    private val integrityManager: IntegrityManager = IntegrityManagerFactory.create(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ProofSignClient"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_VERIFICATION_EXPIRES = "verification_expires"
        private const val PREF_ASSERTION_COUNTER = "assertion_counter"
        private const val PREF_LAST_SERVER_URL = "last_server_url"
        private const val VERIFICATION_VALIDITY_DAYS = 7
        private const val KEYSTORE_ALIAS = "proofsign_device_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }

    init {
        invalidateIfServerChanged()
    }

    /**
     * If the configured server URL has changed since the last registration,
     * drop the cached verification + assertion counter so the next signing
     * attempt re-registers this device against the new server.
     */
    private fun invalidateIfServerChanged() {
        val lastUrl = prefs.getString(PREF_LAST_SERVER_URL, null)
        if (lastUrl != serverUrl) {
            Log.d(TAG, "Server URL changed (was=$lastUrl now=$serverUrl) — invalidating cached verification")
            prefs.edit()
                .putString(PREF_LAST_SERVER_URL, serverUrl)
                .remove(PREF_VERIFICATION_EXPIRES)
                .putLong(PREF_ASSERTION_COUNTER, 0)
                .apply()
        }
    }

    private fun getDeviceId(): String {
        var deviceId = prefs.getString(PREF_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(PREF_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun isVerificationValid(): Boolean {
        val expiresAt = prefs.getLong(PREF_VERIFICATION_EXPIRES, 0)
        return System.currentTimeMillis() < expiresAt
    }

    // region: device keypair

    /**
     * Returns the existing device keypair, generating a non-attested one if none exists.
     * The Play Integrity path uses this directly. The Key Attestation path replaces the
     * key with an attested one via [generateAttestedKeyPair].
     */
    private fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
            val publicKey = keyStore.getCertificate(KEYSTORE_ALIAS).publicKey
            return KeyPair(publicKey, privateKey)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()

        keyPairGenerator.initialize(spec)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Replace the device keypair with one bound to [challenge], returning its
     * Android hardware attestation certificate chain (leaf first, Google root last).
     */
    private fun generateAttestedKeyPair(challenge: ByteArray): List<X509Certificate> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setAttestationChallenge(challenge)
            .build()
        keyPairGenerator.initialize(spec)
        keyPairGenerator.generateKeyPair()

        val chain = keyStore.getCertificateChain(KEYSTORE_ALIAS)
            ?: throw IllegalStateException("Keystore returned no certificate chain")
        return chain.map { it as X509Certificate }
    }

    private fun getPublicKeyBase64(): String {
        val keyPair = getOrCreateKeyPair()
        return Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    private fun signWithDeviceKey(data: ByteArray): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun getAndIncrementCounter(): Long {
        val counter = prefs.getLong(PREF_ASSERTION_COUNTER, 0)
        prefs.edit().putLong(PREF_ASSERTION_COUNTER, counter + 1).apply()
        return counter
    }

    // endregion

    // region: verification

    /**
     * Register this device with the server. Picks the path according to [mode]
     * and Play Store availability.
     */
    fun verifyDevice(callback: (Result<VerificationResult>) -> Unit) {
        val useKeyAttestation =
            mode == AttestationMode.KEY_ATTESTATION || !isPlayIntegrityAvailable()

        if (useKeyAttestation) {
            verifyViaKeyAttestation(callback)
        } else {
            verifyViaPlayIntegrity(callback)
        }
    }

    private fun isPlayIntegrityAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo(PLAY_STORE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    // region: play integrity path

    private fun verifyViaPlayIntegrity(callback: (Result<VerificationResult>) -> Unit) {
        scope.launch {
            try {
                val deviceId = getDeviceId()
                val nonce = requestPlayIntegrityChallenge(deviceId)
                    ?: return@launch dispatch(
                        callback,
                        Result.Failure("Failed to obtain Play Integrity challenge"),
                    )

                requestIntegrityToken(nonce) { tokenResult ->
                    when (tokenResult) {
                        is Result.Success -> {
                            scope.launch {
                                postPlayIntegrityVerification(deviceId, tokenResult.data, nonce, callback)
                            }
                        }
                        is Result.Failure -> dispatch(callback, tokenResult)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Play Integrity verification failed", e)
                dispatch(callback, Result.Failure("Verification failed: ${e.message}", e))
            }
        }
    }

    private fun requestPlayIntegrityChallenge(deviceId: String): String? {
        val json = JSONObject().apply { put("device_id", deviceId) }
        val request = Request.Builder()
            .url("$serverUrl/api/v1/play_integrity/challenge")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "Play Integrity challenge request failed (${response.code}): $body")
                return null
            }
            return JSONObject(body).optString("challenge").takeIf { it.isNotEmpty() }
        }
    }

    private fun requestIntegrityToken(nonce: String, callback: (Result<String>) -> Unit) {
        val request = IntegrityTokenRequest.builder()
            .setCloudProjectNumber(cloudProjectNumber.toLong())
            .setNonce(nonce)
            .build()

        integrityManager.requestIntegrityToken(request)
            .addOnSuccessListener { response: IntegrityTokenResponse ->
                callback(Result.Success(response.token()))
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to get integrity token", exception)
                callback(
                    Result.Failure(
                        "Failed to get integrity token: ${exception.message}",
                        exception as? Exception,
                    ),
                )
            }
    }

    private suspend fun postPlayIntegrityVerification(
        deviceId: String,
        token: String,
        nonce: String,
        callback: (Result<VerificationResult>) -> Unit,
    ) {
        try {
            val publicKey = getPublicKeyBase64()
            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("integrity_token", token)
                put("nonce", nonce)
                put("package_name", context.packageName)
                put("public_key", publicKey)
            }

            val request = Request.Builder()
                .url("$serverUrl/api/v1/play_integrity/verify")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    dispatch(callback, Result.Failure(parseErrorMessage(body, response.code)))
                    return
                }

                val responseJson = JSONObject(body)
                val deviceVerdict = responseJson.optJSONObject("device_integrity")
                    ?.optJSONArray("deviceRecognitionVerdict")
                    ?.let { arr -> (0 until arr.length()).map(arr::getString).joinToString(", ") }
                    ?: "UNKNOWN"
                val appVerdict = responseJson.optJSONObject("app_integrity")
                    ?.optString("appRecognitionVerdict", "UNKNOWN")
                    ?: "UNKNOWN"
                val verdict = "Device: $deviceVerdict, App: $appVerdict"

                persistVerification()
                dispatch(callback, Result.Success(VerificationResult(deviceId, verdict)))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during Play Integrity verification", e)
            dispatch(callback, Result.Failure("Network error: ${e.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Play Integrity server verification failed", e)
            dispatch(callback, Result.Failure("Server verification failed: ${e.message}", e))
        }
    }

    // endregion

    // region: key attestation path

    private fun verifyViaKeyAttestation(callback: (Result<VerificationResult>) -> Unit) {
        scope.launch {
            try {
                val deviceId = getDeviceId()
                val challenge = requestKeyAttestationChallenge(deviceId)
                    ?: return@launch dispatch(
                        callback,
                        Result.Failure("Failed to obtain key attestation challenge"),
                    )

                val chain = generateAttestedKeyPair(challenge.toByteArray())
                val chainBase64 = chain.map {
                    Base64.getEncoder().encodeToString(it.encoded)
                }

                val json = JSONObject().apply {
                    put("device_id", deviceId)
                    put("challenge", challenge)
                    put("certificate_chain", JSONArray(chainBase64))
                }

                val request = Request.Builder()
                    .url("$serverUrl/api/v1/key_attestation/register")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        dispatch(callback, Result.Failure(parseErrorMessage(body, response.code)))
                        return@use
                    }

                    val responseJson = JSONObject(body)
                    val securityLevel = responseJson.optString("security_level", "UNKNOWN")
                    val osVersion = responseJson.optString("os_version", "")
                    val verdict = "KeyAttestation: $securityLevel${if (osVersion.isNotEmpty()) ", OS: $osVersion" else ""}"

                    persistVerification()
                    dispatch(callback, Result.Success(VerificationResult(deviceId, verdict)))
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error during key attestation", e)
                dispatch(callback, Result.Failure("Network error: ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "Key attestation failed", e)
                dispatch(callback, Result.Failure("Key attestation failed: ${e.message}", e))
            }
        }
    }

    private fun requestKeyAttestationChallenge(deviceId: String): String? {
        val json = JSONObject().apply { put("device_id", deviceId) }
        val request = Request.Builder()
            .url("$serverUrl/api/v1/key_attestation/challenge")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "Challenge request failed (${response.code}): $body")
                return null
            }
            return JSONObject(body).optString("challenge").takeIf { it.isNotEmpty() }
        }
    }

    // endregion

    private fun persistVerification() {
        val expiresAt = System.currentTimeMillis() + VERIFICATION_VALIDITY_DAYS * 24L * 60L * 60L * 1000L
        prefs.edit().putLong(PREF_VERIFICATION_EXPIRES, expiresAt).apply()
    }

    // endregion

    // region: signing

    /**
     * Sign a C2PA claim using the device's registered keypair.
     *
     * If the server responds 428 (random integrity re-challenge — Play Integrity
     * devices only), re-runs [verifyDevice] once and retries.
     */
    fun signC2PAClaimWithDeviceAuth(
        claim: String,
        callback: (Result<C2PABearerSignature>) -> Unit,
    ) {
        signC2PAClaimWithDeviceAuth(claim, isRetry = false, callback = callback)
    }

    private fun signC2PAClaimWithDeviceAuth(
        claim: String,
        isRetry: Boolean,
        callback: (Result<C2PABearerSignature>) -> Unit,
    ) {
        scope.launch {
            try {
                if (!isVerificationValid()) {
                    dispatch(callback, Result.Failure("Device verification expired. Call verifyDevice() first"))
                    return@launch
                }

                val deviceId = getDeviceId()
                val counter = getAndIncrementCounter()
                val timestamp = System.currentTimeMillis()
                val dataToSign = "$deviceId|$counter|$timestamp|$claim"
                val requestSignature = signWithDeviceKey(dataToSign.toByteArray())

                val json = JSONObject().apply {
                    put("claim", claim)
                    put("platform", "android")
                    put("token", deviceId)
                    put("counter", counter)
                    put("timestamp", timestamp)
                    put("request_signature", requestSignature)
                }

                val request = Request.Builder()
                    .url("$serverUrl/api/v1/c2pa/sign")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Device-Platform", "android")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val signature = C2PABearerSignature(JSONObject(body).getString("signature"))
                            dispatch(callback, Result.Success(signature))
                        }

                        response.code == 428 && !isRetry -> {
                            Log.i(TAG, "Server requested fresh integrity verification")
                            verifyDevice { verifyResult ->
                                when (verifyResult) {
                                    is Result.Success ->
                                        signC2PAClaimWithDeviceAuth(claim, isRetry = true, callback = callback)
                                    is Result.Failure ->
                                        dispatch(
                                            callback,
                                            Result.Failure("Integrity re-verification failed: ${verifyResult.error}"),
                                        )
                                }
                            }
                        }

                        response.code == 428 && isRetry -> {
                            dispatch(callback, Result.Failure("Device integrity verification failed"))
                        }

                        else -> dispatch(callback, Result.Failure(parseErrorMessage(body, response.code)))
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error during signing", e)
                dispatch(callback, Result.Failure("Network error: ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "Signing failed", e)
                dispatch(callback, Result.Failure("Signing failed: ${e.message}", e))
            }
        }
    }

    // endregion

    private fun parseErrorMessage(body: String, statusCode: Int): String =
        try {
            JSONObject(body).optString("message").ifEmpty { "Request failed with $statusCode: $body" }
        } catch (_: Exception) {
            "Request failed with $statusCode: $body"
        }

    private fun <T> dispatch(callback: (Result<T>) -> Unit, result: Result<T>) {
        scope.launch { withContext(Dispatchers.Main) { callback(result) } }
    }
}

data class C2PABearerSignature(val signature: String)
