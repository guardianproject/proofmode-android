package org.witness.proofmode.c2pa.proofsign


import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.contentauth.c2pa.C2PAJson
import org.contentauth.c2pa.Signer
import org.contentauth.c2pa.SigningAlgorithm
import org.witness.proofmode.library.BuildConfig
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * ProofSignC2PASigner provides remote signing capabilities through a ProofSign C2PA signing server,
 * with integrated support for play-integry checking
 *
 * Example usage:
 * ```kotlin
 * val proofSignC2PASigner = ProofSignC2PASigner(
 *     context,
 *     serverUrl = "https://proofsign.example.com",
 * )
 *
 * val signer = proofSignC2PASigner.createSigner()
 * ```
 */
class ProofSignC2PASigner (
    private val context: Context,
    private val serverUrl: String,
    private val timestampUrlOverride: String
) {

    private val configurationURL: String = serverUrl.trimEnd('/') + BuildConfig.API_ENDPOINT

    init {
        Timber.d("ProofSignC2PASigner: serverUrl=%s configurationURL=%s", serverUrl, configurationURL)
    }

    private val proofSignClient = ProofSignClient(
        context = context,
        serverUrl = serverUrl.trimEnd('/'),
        cloudProjectNumber = BuildConfig.CLOUD_INTEGRITY_PROJECT_NUMBER,
    )

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Creates a Signer instance configured for remote signing. This method fetches the
     * configuration from the server and sets up the signing callback.
     */
    suspend fun createSigner(): Signer {
        val configuration = fetchConfiguration()
        val signingAlgorithm = mapAlgorithm(configuration.algorithm)
        val certificateChain = parseCertificateChain(configuration.certificate_chain)

        var configTsaUrl = configuration.timestamp_url.takeIf { it.isNotEmpty() }
        if (timestampUrlOverride.isNotEmpty())
            configTsaUrl = timestampUrlOverride

        timestampUrlOverride
        return Signer.withCallback(
            algorithm = signingAlgorithm,
            certificateChainPEM = certificateChain,
            tsaURL = configTsaUrl,
        ) { data -> signData(data) }
    }

    private suspend fun fetchConfiguration(): SignerConfiguration {
        val url = configurationURL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.setQueryParameter("platform", "android")
            ?.build()
            ?: throw org.contentauth.c2pa.SignerException.InvalidResponse

        Timber.d("ProofSign: GET %s", url)

        val requestBuilder =
            Request.Builder().url(url).get().header("Accept", "application/json")

        val response = httpClient.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            Timber.w("ProofSign: config fetch returned %d for %s — body=%s", response.code, url, body)
            throw org.contentauth.c2pa.SignerException.HttpError(response.code)
        }

        val responseBody = response.body?.string() ?: throw org.contentauth.c2pa.SignerException.InvalidResponse

        return C2PAJson.default.decodeFromString(responseBody)
    }

    private fun mapAlgorithm(algorithmString: String): SigningAlgorithm = when (algorithmString.lowercase()) {
        "es256" -> SigningAlgorithm.ES256
        "es384" -> SigningAlgorithm.ES384
        "es512" -> SigningAlgorithm.ES512
        "ps256" -> SigningAlgorithm.PS256
        "ps384" -> SigningAlgorithm.PS384
        "ps512" -> SigningAlgorithm.PS512
        "ed25519" -> SigningAlgorithm.ED25519
        else -> throw SignerException.UnsupportedAlgorithm(algorithmString)
    }

    private fun signData(data: ByteArray): ByteArray {


       // Log.i("ProofSignC2PA", "During welcomes: $apiToken")


        // Inner gate: refuse if this thread was not opened into a signing
        // scope by C2PAManager.signMediaFile. A direct Frida call into this
        // method (e.g. via a held c2pa-rs Signer reference) bypasses the
        // outer gates but has no authorized digest on this thread.
        if (CaptureAuthority.currentAuthorizedDigest() == null) {
            throw UnauthorizedCaptureException(
                "ProofSignC2PASigner.signData called outside an authorized capture-signing scope"
            )
        }
        if (!proofSignClient.isDeviceRegistered()) {
            val verifyLatch = java.util.concurrent.CountDownLatch(1)
            var verifyError: String? = null

            proofSignClient.verifyDevice() { result -> when (result) {
                                         is Result.Success -> {}
                                         is Result.Failure -> {
                                             verifyError = result.error
                                         }
                                     }
                                 verifyLatch.countDown()
            }


        if (!verifyLatch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                throw SignerException.HttpError(-1, "Device verification timed out")
            }

            verifyError?.let { throw SignerException.HttpError(-1, "Device verification failed: $it") }
        }

        val dataToSignBase64 = Base64.encodeToString(data, Base64.NO_WRAP)

        val latch = java.util.concurrent.CountDownLatch(1)
        var signedData: ByteArray? = null
        var signingError: String? = null

        proofSignClient.signC2PAClaimWithDeviceAuth(dataToSignBase64) {
            when (it) {
                is Result.Success -> {
                    signedData = Base64.decode(it.data.signature, Base64.NO_WRAP)
                }
                is Result.Failure -> {
                    signingError = it.error
                }
            }
            latch.countDown()
        }

        if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
            throw SignerException.InvalidResponse
        }

        signingError?.let { throw SignerException.HttpError(-1, it) }

        return signedData ?: throw SignerException.InvalidSignature
    }

    private fun parseCertificateChain(base64Chain: String): String {
        val chainData = Base64.decode(base64Chain, Base64.DEFAULT)
        val chainString = String(chainData, Charsets.UTF_8)

        if (!chainString.contains("BEGIN CERTIFICATE") || !chainString.contains("END CERTIFICATE")) {
            throw SignerException.InvalidCertificateChain
        }

        return chainString
    }

    @Serializable
    private data class SignerConfiguration(
        val algorithm: String,
        val timestamp_url: String,
        val signing_url: String,
        val certificate_chain: String,
    )
}

/** Exceptions specific to signer operations */
sealed class SignerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object InvalidResponse : SignerException("Invalid response from server")
    data class HttpError(val statusCode: Int, val body: String? = null) :
        SignerException("HTTP error: $statusCode${body?.let { " - $it" } ?: ""}")
    data class UnsupportedAlgorithm(val algorithm: String) :
        SignerException("Unsupported algorithm: $algorithm")
    object InvalidCertificateChain : SignerException("Invalid certificate chain")
    object InvalidSignature : SignerException("Invalid signature format")
}
