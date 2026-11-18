package org.witness.proofmode.c2pa.tsa

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.tsp.TimeStampRequest
import org.witness.proofmode.c2pa.proofsign.DeviceRequestSigner
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** ProofSign refused the device's credentials: unregistered, or a bad or stale signature. */
class TsaAuthException(message: String) : IOException(message)

/** The TSA could not be reached or answered 502/503, even after one retry. */
class TsaUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun HttpUrl.isLoopback(): Boolean = host == "127.0.0.1" || host == "::1" || host == "localhost"

/**
 * Relays a time-stamp request to ProofSign's `/api/v1/timestamp`, which holds the TSA key
 * server-side. c2pa-rs only takes a bare `tsaURL` and cannot add headers, so each request
 * passes through here and gets `X-Device-ID`, `X-Device-Timestamp` and `X-Device-Signature`,
 * signed by the device key over the exact bytes posted. The reply is the TSA's signed token
 * and passes back untouched.
 *
 * Only a well-formed RFC 3161 `TimeStampReq` is ever signed. The signature input has the
 * same shape as the sign request's `device_signature`, so signing arbitrary bytes here
 * would hand out device signatures over anything that reached the relay.
 */
class TsaForwarder(
    private val upstream: HttpUrl,
    private val signer: DeviceRequestSigner,
    private val client: OkHttpClient = defaultClient,
    private val retryDelayMs: Long = 500,
) : TsaHandler {

    init {
        // Plain http only to a dev server on this device.
        require(upstream.isHttps || upstream.isLoopback()) { "ProofSign TSA must be https" }
    }

    companion object {
        /** ProofSign rejects larger queries with 413; a TimeStampReq is a hash and a few fields. */
        const val MAX_QUERY_BYTES = 4096

        /**
         * No redirects: the device headers would follow a redirect to whatever host it named,
         * and ProofSign never redirects this endpoint.
         */
        private val defaultClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun handle(query: ByteArray): ByteArray {
        if (query.size > MAX_QUERY_BYTES) throw IOException("Refusing a ${query.size}-byte time-stamp query")
        try {
            TimeStampRequest(query)
        } catch (e: Exception) {
            throw IOException("Refusing to sign a body that is not a TimeStampReq", e)
        }

        return try {
            attempt(query)
        } catch (e: TsaUnavailableException) {
            Timber.w("ProofSign TSA: %s - retrying once", e.message)
            if (retryDelayMs > 0) {
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    // TsaProxy closing its server interrupts in-flight workers.
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted before retrying the TSA")
                }
            }
            attempt(query)
        }
    }

    /** One POST, with headers signed afresh so the retry carries a current timestamp. */
    private fun attempt(query: ByteArray): ByteArray {
        // Runs on a LocalTsaServer worker: anything but an IOException would kill the process.
        val auth = try {
            signer.headersFor(query)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not sign the time-stamp query with the device key", e)
        }
        val request = Request.Builder()
            .url(upstream)
            .header("X-Device-ID", auth.deviceId)
            .header("X-Device-Timestamp", auth.timestampMillis.toString())
            .header("X-Device-Signature", auth.signature)
            .header("Accept", TimeStampResponder.CONTENT_TYPE_REPLY)
            .post(query.toRequestBody(TimeStampResponder.CONTENT_TYPE_QUERY.toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw TsaUnavailableException("TSA ${upstream.host} unreachable: ${e.message}", e)
        }
        response.use {
            when (it.code) {
                401 -> {
                    val serverTime = it.headers.getDate("Date")?.time
                    val skew = serverTime?.let { t -> "${t - auth.timestampMillis}ms" } ?: "unknown"
                    // Clock skew or a missing registration; never log the signature.
                    val message = "ProofSign TSA rejected device=${auth.deviceId} (HTTP 401, skew=$skew)"
                    Timber.w(message)
                    throw TsaAuthException(message)
                }
                502, 503 -> throw TsaUnavailableException("TSA ${upstream.host} answered HTTP ${it.code}")
            }
            // Includes a 3xx: redirects are not followed, and not relayed as a token.
            if (!it.isSuccessful) throw IOException("TSA ${upstream.host} answered HTTP ${it.code}")
            val contentType = it.header("Content-Type")?.substringBefore(';')?.trim()
            if (!contentType.equals(TimeStampResponder.CONTENT_TYPE_REPLY, ignoreCase = true)) {
                throw IOException("TSA ${upstream.host} answered $contentType, not a timestamp reply")
            }
            val body = it.body ?: throw IOException("TSA ${upstream.host} sent no body")
            return try {
                body.bytes()
            } catch (e: IOException) {
                throw TsaUnavailableException("TSA ${upstream.host} reply cut off: ${e.message}", e)
            }
        }
    }
}

/**
 * A loopback [LocalTsaServer] fronting ProofSign's timestamp endpoint, so c2pa-rs can be
 * pointed at it as a plain URL. The URL carries a random path: other apps share loopback
 * and could otherwise get time-stamp queries signed with this device's key.
 */
object TsaProxy {
    private var server: LocalTsaServer? = null
    private var upstreamOf: HttpUrl? = null
    private val random = SecureRandom()

    /** Loopback URL relaying to [upstreamUrl] with [signer]'s headers; null if it can't be started. */
    @Synchronized
    fun urlFor(upstreamUrl: String, signer: DeviceRequestSigner): String? {
        val upstream = upstreamUrl.trim().toHttpUrlOrNull() ?: return null
        server?.let {
            if (it.isRunning && upstreamOf == upstream) return it.url
            it.close()
        }
        server = null
        if (!upstream.isHttps && !upstream.isLoopback()) {
            Timber.w("TsaProxy: refusing to send device credentials to non-https %s", upstream.host)
            return null
        }
        return try {
            LocalTsaServer(TsaForwarder(upstream, signer), requiredPath = "/" + randomToken()).also {
                server = it
                upstreamOf = upstream
            }.url
        } catch (e: IOException) {
            Timber.w(e, "TsaProxy: could not start")
            null
        }
    }

    @Synchronized
    internal fun stop() {
        server?.close()
        server = null
        upstreamOf = null
    }

    private fun randomToken(): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
