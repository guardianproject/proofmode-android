package org.witness.proofmode.c2pa.tsa

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Relays a time-stamp request to [upstream] with an `X-API-Key` header, which
 * c2pa-rs has no way to send itself (its `tsaURL` is just a URL). The reply is
 * passed back untouched: it is the remote TSA's signed token, not ours.
 */
class TsaForwarder(
    private val upstream: HttpUrl,
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient,
) : TsaHandler {

    init {
        // Never put the key on the wire in the clear.
        require(upstream.isHttps) { "API-keyed TSA must be https" }
    }

    companion object {
        /**
         * No redirects: OkHttp strips Authorization on a cross-host redirect but not a
         * custom header like X-API-Key, so following one could hand the key to another host.
         */
        private val defaultClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun handle(query: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(upstream)
            .header("X-API-Key", apiKey)
            .header("Accept", TimeStampResponder.CONTENT_TYPE_REPLY)
            .post(query.toRequestBody(TimeStampResponder.CONTENT_TYPE_QUERY.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            // Trufo answers auth failures with 401 + JSON; relaying that as a token
            // would only make c2pa-rs fail later with a less useful error.
            if (!response.isSuccessful) throw IOException("TSA ${upstream.host} answered HTTP ${response.code}")
            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
            if (!contentType.equals(TimeStampResponder.CONTENT_TYPE_REPLY, ignoreCase = true)) {
                throw IOException("TSA ${upstream.host} answered $contentType, not a timestamp reply")
            }
            return response.body?.bytes() ?: throw IOException("TSA ${upstream.host} sent no body")
        }
    }
}

/**
 * A loopback [LocalTsaServer] fronting one API-keyed remote TSA, so c2pa-rs can be
 * pointed at it as a plain URL. The URL carries a random path: other apps share
 * loopback and could otherwise spend our API quota through it.
 */
object TsaProxy {
    private var server: LocalTsaServer? = null
    private var upstreamOf: HttpUrl? = null
    private val random = SecureRandom()

    /** Loopback URL forwarding to [upstreamUrl] with [apiKey]; null if it can't be started. */
    @Synchronized
    fun urlFor(upstreamUrl: String, apiKey: String): String? {
        val upstream = upstreamUrl.trim().toHttpUrlOrNull() ?: return null
        server?.let {
            if (it.isRunning && upstreamOf == upstream) return it.url
            it.close()
        }
        server = null
        if (!upstream.isHttps) {
            Timber.w("TsaProxy: refusing to send the API key to non-https %s", upstream.host)
            return null
        }
        return try {
            LocalTsaServer(TsaForwarder(upstream, apiKey), requiredPath = "/" + randomToken()).also {
                server = it
                upstreamOf = upstream
            }.url
        } catch (e: IOException) {
            Timber.w(e, "TsaProxy: could not start")
            null
        }
    }

    private fun randomToken(): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
