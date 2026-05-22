package org.witness.proofmode.c2pa.proofsign

/**
 * ProofSign Android Client.
 *
 * Remote signing requires Google Play Integrity. The device registers a
 * hardware key-attested EC P-256 keypair (Android Keystore alias
 * `proofsign_device_key`) once with the server via Android key attestation;
 * thereafter every /api/v1/c2pa/sign call carries:
 *
 *   - a device-key signature over the ASCII string
 *     `device_id|timestamp|claimBinding`, attributing the sign to the
 *     registered key, and
 *   - a fresh Standard Play Integrity token whose requestHash is the same
 *     `claimBinding`.
 *
 * `claimBinding` is the single canonical claim digest shared by both bindings
 * (and, server-side, by the iOS App Attest path): `Base64Std(SHA-256(
 * base64Decode(claim)))` — over the decoded claim bytes. Computed once and
 * reused, so there is no string-vs-decoded asymmetry (GP-132/GP-133).
 *
 * Devices without Play Services never reach this client — the caller falls
 * back to local signing; see [ProofSignClient.isPlayIntegrityAvailable].
 */

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityToken
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import info.guardianproject.durindoor.Native
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.witness.proofmode.library.BuildConfig

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure(val error: String, val exception: Exception? = null) : Result<Nothing>()
}

data class VerificationResult(val deviceId: String, val verdict: String)

class ProofSignClient(
    private val context: Context,
    private val serverUrl: String,
    private val cloudProjectNumber: String,
) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("proofsign_prefs", Context.MODE_PRIVATE)

    private val standardIntegrityManager: StandardIntegrityManager =
        IntegrityManagerFactory.createStandard(context)

    /** Cached, warmed Standard Play Integrity provider. Dropped on request failure. */
    @Volatile
    private var tokenProvider: StandardIntegrityTokenProvider? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ProofSignClient"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_DEVICE_REGISTERED = "device_registered"
        private const val PREF_LAST_SERVER_URL = "last_server_url"
        private const val KEYSTORE_ALIAS = "proofsign_device_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        /**
         * Whether this device can use Play Integrity (Play Store present).
         * Remote signing requires this; callers MUST fall back to local
         * signing when it returns false (no-Play devices never contact the
         * ProofSign server).
         */
        fun isPlayIntegrityAvailable(context: Context): Boolean =
            try {
                context.packageManager.getPackageInfo(PLAY_STORE_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
    }

    init {
        Log.d(TAG, "ProofSignClient init: serverUrl=$serverUrl")
        invalidateIfServerChanged()
    }

    /**
     * If the configured server URL changed since the last registration, drop
     * the cached registration so the next signing attempt re-registers this
     * device against the new server.
     */
    private fun invalidateIfServerChanged() {
        val lastUrl = prefs.getString(PREF_LAST_SERVER_URL, null)
        if (lastUrl != serverUrl) {
            Log.d(TAG, "Server URL changed (was=$lastUrl now=$serverUrl) — invalidating registration")
            prefs.edit()
                .putString(PREF_LAST_SERVER_URL, serverUrl)
                .putBoolean(PREF_DEVICE_REGISTERED, false)
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

    /** Whether this device has completed key-attestation registration with the current server. */
    fun isDeviceRegistered(): Boolean = prefs.getBoolean(PREF_DEVICE_REGISTERED, false)

    private fun persistRegistration() {
        prefs.edit().putBoolean(PREF_DEVICE_REGISTERED, true).apply()
    }

    // region: device keypair

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

    private fun signWithDeviceKey(data: ByteArray): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    /** Decode a hex string (server-issued KA challenge) into its raw bytes. */
    private fun hexDecode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Challenge is not valid hex (odd length)" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // endregion

    // region: registration (key attestation)

    /**
     * Register this device with the server by presenting an Android hardware
     * key attestation certificate chain. This is the universal identity
     * bootstrap and only ever runs on the remote path (Play Integrity-capable
     * devices — gated by the caller via [isPlayIntegrityAvailable]).
     */
    fun verifyDevice(callback: (Result<VerificationResult>) -> Unit) {
        scope.launch {
            try {
                val deviceId = getDeviceId()
                val challenge = requestKeyAttestationChallenge(deviceId)
                    ?: return@launch dispatch(
                        callback,
                        Result.Failure("Failed to obtain key attestation challenge"),
                    )

                // The server issues the challenge as hex(rand[32]); proofmode
                // binds the DECODED challenge bytes into the attestation cert,
                // so embed hexDecode(challenge) — not the hex string's ASCII.
                // The register body still sends the original hex string (the
                // server matches that against its challenge table).
                val chain = generateAttestedKeyPair(hexDecode(challenge))
                val chainBase64 = chain.map { Base64.getEncoder().encodeToString(it.encoded) }

                val json = JSONObject().apply {
                    put("device_id", deviceId)
                    put("challenge", challenge)
                    put("certificate_chain", JSONArray(chainBase64))
                }

                val url = "$serverUrl/api/v1/key_attestation/register"
                Log.d(TAG, "POST $url")
                val request = Request.Builder()
                    .url(url)
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
                    val verdict = "KeyAttestation: $securityLevel" +
                        if (osVersion.isNotEmpty()) ", OS: $osVersion" else ""

                    persistRegistration()
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
        val url = "$serverUrl/api/v1/key_attestation/challenge"
        Log.d(TAG, "POST $url")
        val json = JSONObject().apply { put("device_id", deviceId) }
        val request = Request.Builder()
            .url(url)
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

    // region: standard play integrity

    /**
     * Returns a cached [StandardIntegrityTokenProvider], preparing one if
     * needed. Google recommends keeping the provider warm and refreshing it
     * on failure (see [requestIntegrityToken]).
     */
    private suspend fun getTokenProvider(): StandardIntegrityTokenProvider {
        tokenProvider?.let { return it }
        return suspendCancellableCoroutine { cont ->
            standardIntegrityManager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber.toLong())
                    .build(),
            )
                .addOnSuccessListener { provider ->
                    tokenProvider = provider
                    cont.resume(provider)
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }
    }

    /**
     * Requests a Standard Play Integrity token whose requestHash binds the
     * verdict to [requestHash]. On failure the cached provider is dropped so
     * the next attempt re-prepares it.
     */
    private suspend fun requestIntegrityToken(requestHash: String): String {
        val provider = getTokenProvider()
        return try {
            suspendCancellableCoroutine { cont ->
                provider.request(
                    StandardIntegrityTokenRequest.builder()
                        .setRequestHash(requestHash)
                        .build(),
                )
                    .addOnSuccessListener { token: StandardIntegrityToken ->
                        cont.resume(token.token())
                    }
                    .addOnFailureListener { e ->
                        cont.resumeWithException(e)
                    }
            }
        } catch (e: Exception) {
            tokenProvider = null
            throw e
        }
    }

    // endregion

    // region: signing

    /**
     * Sign a C2PA claim using the device's registered key plus a fresh,
     * claim-bound Standard Play Integrity token.
     *
     * Requires [isDeviceRegistered]; the caller must have completed
     * [verifyDevice] first. Posts `claim`, `device_id`, `timestamp` (epoch
     * millis, required for server freshness + part of the signed payload),
     * the device-key signature over `device_id|timestamp|claimBinding`, and a
     * Standard Play Integrity token whose requestHash is that same
     * `claimBinding` (= `Base64(SHA-256(base64Decode(claim)))`). The server
     * recomputes the one binding and rejects on any mismatch.
     */
    fun signC2PAClaimWithDeviceAuth(
        claim: String,
        callback: (Result<C2PABearerSignature>) -> Unit,
    ) {
        // Runtime integrity recheck — the startup check in onCreate is
        // bypassed by an attacker who attaches Frida AFTER launch. Runs the
        // native /proc/self/maps + port + thread + syscall + timing probes.
        if (org.witness.proofmode.c2pa.DeviceIntegritySupport().isEnvironmentCompromised()) {
            throw CompromisedEnvironmentException(
                "signC2PAClaimWithDeviceAuth refused: runtime environment is compromised"
            )
        }
        // Defense against the documented Frida oracle attack: an attacker
        // who Java.use()s this class and invokes this method directly never
        // traversed the camera capture path and never entered an authorized
        // signing scope. We check synchronously, before launching the
        // coroutine, so the throw reaches the attacker rather than being
        // swallowed by coroutine machinery.
        if (CaptureAuthority.currentAuthorizedDigest() == null) {
            throw UnauthorizedCaptureException(
                "signC2PAClaimWithDeviceAuth called outside an authorized capture-signing scope"
            )
        }
        scope.launch {
            try {
                if (!isDeviceRegistered()) {
                    dispatch(callback, Result.Failure("Device not registered. Call verifyDevice() first"))
                    return@launch
                }

                //First check if this is a friend of the Elves and Dwarves
                var apiToken = ""

                if (Native.nativePing() != null)
                {
                    apiToken = Native.nativeToken("mellon");

                    if (!BuildConfig.DEBUG) {
                        if (apiToken.isEmpty()) {
                            //   Log.i("ProofSignC2PA", "durin stands: apiToken is empty")
                            throw SignerException.HttpError(
                                -1,
                                "Security gate failure: native token missing"
                            )
                        }
                    }
                }

                val deviceId = getDeviceId()
                val timestamp = System.currentTimeMillis()
                // One canonical claim binding, shared by the device signature
                // and the Standard token requestHash (GP-132/GP-133), pinned
                // cross-language by claim_binding_vectors.json (see
                // [ClaimBinding] / ClaimBindingConformanceTest).
                val claimBinding = ClaimBinding.claimBinding(Base64.getDecoder().decode(claim))
                val signingInput =
                    ClaimBinding.deviceRequestSigningInput(deviceId, timestamp, claimBinding)
                val deviceSignature = signWithDeviceKey(signingInput.toByteArray())
                val requestHash = claimBinding

                val integrityToken = try {
                    requestIntegrityToken(requestHash)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get integrity token", e)
                    dispatch(callback, Result.Failure("Failed to get integrity token: ${e.message}", e))
                    return@launch
                }

                val json = JSONObject().apply {
                    if (!apiToken.isEmpty())
                        put ("token", apiToken)

                    put("claim", claim)
                    put("platform", "android")
                    put("device_id", deviceId)
                    put("timestamp", timestamp)
                    // Server's canonical field is `request_signature`;
                    // `device_signature` is accepted as a serde alias.
                    put("device_signature", deviceSignature)
                    put("integrity_token", integrityToken)
                }

                val url = "$serverUrl/api/v1/c2pa/sign"
                Log.d(TAG, "POST $url")
                val request = Request.Builder()
                    .url(url)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Device-Platform", "android")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val signature = C2PABearerSignature(JSONObject(body).getString("signature"))
                        dispatch(callback, Result.Success(signature))
                    } else {
                        dispatch(callback, Result.Failure(parseErrorMessage(body, response.code)))
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
