package org.witness.proofmode.storage.filebase

import android.net.Uri
import android.util.Log
import java.io.*
import java.net.URLEncoder
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.proofset.DeferredArtifact
import org.witness.proofmode.storage.proofset.ProofSetContentTypes

interface TestConnectionCallback {
    fun onTestSuccess()
    fun onTestFailure(error: String)
}

open class FilebaseStorageProvider(
    private val accessKey: String,
    private val secretKey: String,
    private val bucketName: String,
    private val endpoint: String = "https://s3.filebase.com",
    private val region: String = "us-east-1",
    private val ipfsBearerToken: String = "",
) : StorageProvider {

    companion object {
        private const val TAG = "FilebaseStorageProvider"
    //    private const val REGION = "us-east-1"
        private const val SERVICE = "s3"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val DATE_FORMAT = "yyyyMMdd'T'HHmmss'Z'"
        private const val DATE_STAMP_FORMAT = "yyyyMMdd"
        private const val IPFS_RPC_BASE_URL = "https://rpc.filebase.io"
        /** Directory `/api/v0/add` query — Filebase-supported params only. */
        private const val IPFS_ADD_PARAM = "wrap-with-directory=true&cid-version=1"

        /**
         * Parse a CID from IPFS RPC NDJSON.
         *
         * - [name] `null`: first non-blank `Hash` (standalone file add)
         * - [name] `""`: wrap-with-directory wrapper (`Name` exactly empty)
         * - otherwise: first line whose `Name` equals [name] (e.g. media basename)
         */
        @JvmStatic
        fun parseCid(responseBody: String?, name: String? = null): String? {
            if (responseBody.isNullOrBlank()) {
                return null
            }
            // Reject whitespace-only names; empty string "" is the directory wrapper sentinel.
            if (name != null && name.isNotEmpty() && name.isBlank()) {
                return null
            }

            try {
                for (line in responseBody.trim().lineSequence()) {
                    if (line.isBlank()) continue
                    val json = JSONObject(line)
                    if (!json.has("Hash")) continue
                    val hash = json.getString("Hash")
                    if (hash.isBlank()) continue

                    when (name) {
                        null -> return hash
                        else -> {
                            if (!json.has("Name")) continue
                            if (json.getString("Name") == name) return hash
                        }
                    }
                }
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing IPFS RPC CID (name=$name)", e)
                return null
            }
        }

        /**
         * CID of the link named [name] in an `/api/v0/ls` response, or `null` when absent.
         *
         * Shape: `{"Objects":[{"Hash":"<root>","Links":[{"Name":"x.jpg","Hash":"bafy…"},…]}]}`.
         */
        @JvmStatic
        fun parseDirectoryLeafCid(responseBody: String?, name: String): String? {
            if (responseBody.isNullOrBlank() || name.isBlank()) {
                return null
            }

            return try {
                val objects = JSONObject(responseBody).optJSONArray("Objects") ?: return null
                for (i in 0 until objects.length()) {
                    val links = objects.optJSONObject(i)?.optJSONArray("Links") ?: continue
                    for (j in 0 until links.length()) {
                        val link = links.optJSONObject(j) ?: continue
                        if (link.optString("Name") != name) continue
                        return link.optString("Hash").takeIf { it.isNotBlank() }
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing IPFS ls response (name=$name)", e)
                null
            }
        }

        @JvmStatic
        fun buildUnpinUrl(cid: String): String {
            val encodedCid = URLEncoder.encode(cid, "UTF-8")
            return "$IPFS_RPC_BASE_URL/api/v0/pin/rm?arg=$encodedCid"
        }

        fun from(config: FilebaseConfig): FilebaseStorageProvider = FilebaseStorageProvider(
            accessKey = config.accessKey,
            secretKey = config.secretKey,
            bucketName = config.bucketName,
            endpoint = config.endpoint,
            region = config.region,
            ipfsBearerToken = config.ipfsBearerToken,
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {
        try {
            val tempFile = File.createTempFile("filebase_upload", ".tmp")

            stream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            uploadFile(hash, identifier, tempFile, listener)
            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving stream to Filebase", e)
            listener?.saveFailed(e)
        }
    }

    open override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener?) {
        try {
            val tempFile = File.createTempFile("filebase_upload", ".tmp")
            tempFile.writeBytes(data)

            uploadFile(hash, identifier, tempFile, listener)
            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bytes to Filebase", e)
            listener?.saveFailed(e)
        }
    }

    override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {
        uploadTextObject(hash, identifier, data, listener)
    }

    /** S3 object PUT replaces the key; same path as [saveText]. */
    override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener?) {
        uploadTextObject(hash, identifier, data, listener)
    }

    private fun uploadTextObject(
        hash: String,
        identifier: String,
        data: String,
        listener: StorageListener?,
    ) {
        try {
            val tempFile = File.createTempFile("filebase_upload", ".tmp")
            tempFile.writeText(data)
            uploadFile(hash, identifier, tempFile, listener)
            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving text to Filebase", e)
            listener?.saveFailed(e)
        }
    }

    /**
     * Upload a flat proof-set directory via Filebase IPFS RPC (`wrap-with-directory`).
     * Multipart filenames are [DeferredArtifact.identifier] basenames only (no `hash/` prefix).
     *
     * Returns [FilebaseUploadResult] on success (directory gateway URI + optional media-leaf CID
     * for [mediaBasename]), or `null` on failure. [listener] is forward-only UX; persist
     * decisions must use the return value, not the listener.
     */
    open fun uploadDirectory(
        hash: String,
        artifacts: List<DeferredArtifact>,
        mediaBasename: String?,
        listener: StorageListener?,
    ): FilebaseUploadResult? {
        if (ipfsBearerToken.isBlank()) {
            val error = IllegalStateException(
                "IPFS Bearer Token not configured. Please set it in Filebase settings.",
            )
            Log.e(TAG, "IPFS upload failed: missing bearer token")
            listener?.saveFailed(error)
            return null
        }

        return try {
            Log.d(TAG, "Uploading directory for hash: $hash with ${artifacts.size} files")

            val multipartBody = buildIpfsMultipart(artifacts)
            val url = "$IPFS_RPC_BASE_URL/api/v0/add?$IPFS_ADD_PARAM"
            val (code, body) = postIpfsRpc(url, multipartBody)

            if (!isIpfsAddSuccess(code) || body.isNullOrBlank()) {
                val error = IOException("IPFS RPC upload failed: $code")
                Log.e(TAG, "Directory upload failed", error)
                listener?.saveFailed(error)
                return null
            }

            val directoryCid = parseCid(body, name = "")
            if (directoryCid == null) {
                val error = IOException("Failed to parse directory CID from response")
                Log.e(TAG, "CID parsing failed", error)
                listener?.saveFailed(error)
                return null
            }

            val mediaLeafCid = if (!mediaBasename.isNullOrBlank()) {
                parseCid(body, name = mediaBasename)
            } else {
                null
            }

            val ipfsUrl = "https://ipfs.filebase.io/ipfs/$directoryCid"
            Log.d(TAG, "Successfully uploaded directory: $ipfsUrl")
            listener?.saveSuccessful(hash, ipfsUrl)
            FilebaseUploadResult(ipfsUrl, mediaLeafCid)
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading directory to Filebase IPFS RPC", e)
            listener?.saveFailed(e)
            null
        }
    }

    /**
     * Build a flat multipart form for IPFS `/add`.
     *
     * Parts stream from their [DeferredArtifact] sources, so a multi-hundred-megabyte media leaf
     * is written to the socket in fixed-size chunks and never sits on the heap.
     */
    private fun buildIpfsMultipart(artifacts: List<DeferredArtifact>): MultipartBody {
        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        for (artifact in artifacts) {
            val mediaType = if (artifact.contentType.isNullOrBlank()) {
                "application/octet-stream"
            } else {
                artifact.contentType
            }
            multipartBuilder.addFormDataPart(
                "file",
                artifact.identifier,
                StreamRequestBody(mediaType.toMediaType(), artifact.length) { artifact.openStream() },
            )
        }
        return multipartBuilder.build()
    }

    /**
     * Request body that opens its source per write instead of holding it.
     *
     * OkHttp may call [writeTo] more than once (retry / redirect / auth challenge), so [open] must
     * hand back a fresh stream every time.
     */
    private class StreamRequestBody(
        private val mediaType: MediaType?,
        private val length: Long,
        private val open: () -> InputStream,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            open().use { input -> sink.writeAll(input.source()) }
        }
    }

    /** Single POST seam for every IPFS RPC call, so tests can stub it without a socket. */
    protected open fun postIpfsRpc(url: String, body: RequestBody): Pair<Int, String?> {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $ipfsBearerToken")
            .build()
        Log.d(TAG, "Sending IPFS RPC request to: $url")
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            Log.d(TAG, "IPFS RPC response code: ${response.code}")
            return response.code to responseBody
        }
    }

    private fun isIpfsAddSuccess(code: Int): Boolean = code in 200..299

    /**
     * Best-effort unpin. Returns true if RPC reports success; false on blank token,
     * blank cid, network/HTTP failure. Never throws to callers.
     */
    open fun unpinIpfsCid(cid: String): Boolean {
        if (ipfsBearerToken.isBlank() || cid.isBlank()) {
            return false
        }

        return try {
            val url = buildUnpinUrl(cid)
            val request = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .addHeader("Authorization", "Bearer $ipfsBearerToken")
                .build()
            Log.d(TAG, "Sending IPFS unpin request to: $url")
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "IPFS unpin response code: ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unpinning IPFS CID", e)
            false
        }
    }

    /**
     * Names the leaf of [directoryCid] called [name], or `null` when the directory has no such
     * link, the RPC declines `/ls`, or the response cannot be parsed.
     *
     * This is the "is the media already pinned?" probe: it answers from the pinned directory's own
     * link table, so it costs one metadata round trip whatever the media weighs, and — unlike
     * deriving a URL from the directory CID alone — a hit means the leaf really is there.
     */
    open fun findIpfsDirectoryLeafCid(directoryCid: String, name: String): String? {
        if (ipfsBearerToken.isBlank() || directoryCid.isBlank() || name.isBlank()) {
            return null
        }

        return try {
            val url = "$IPFS_RPC_BASE_URL/api/v0/ls?arg=${URLEncoder.encode(directoryCid, "UTF-8")}"
            val (code, body) = postIpfsRpc(url, ByteArray(0).toRequestBody(null))
            if (!isIpfsAddSuccess(code) || body.isNullOrBlank()) {
                Log.d(TAG, "IPFS ls unavailable for $directoryCid: HTTP $code")
                return null
            }
            parseDirectoryLeafCid(body, name)
        } catch (e: Exception) {
            Log.d(TAG, "Error listing IPFS directory $directoryCid", e)
            null
        }
    }

    /**
     * Standalone IPFS file add (no wrap-with-directory) for social.
     * On success returns gateway leaf URI; on failure null.
     *
     * [artifact] is streamed to the socket — social share is reached with the original capture, so
     * this runs against files far larger than the heap.
     */
    open fun uploadFileIpfs(artifact: DeferredArtifact): String? {
        if (ipfsBearerToken.isBlank() || artifact.identifier.isBlank()) {
            return null
        }

        return try {
            val multipartBody = buildIpfsMultipart(listOf(artifact))
            val url = "$IPFS_RPC_BASE_URL/api/v0/add?cid-version=1"
            val (code, body) = postIpfsRpc(url, multipartBody)
            if (!isIpfsAddSuccess(code) || body.isNullOrBlank()) {
                Log.e(TAG, "IPFS file add failed: HTTP $code")
                return null
            }

            val leafCid = parseCid(body)
            if (leafCid == null) {
                Log.e(TAG, "Failed to parse file CID from IPFS RPC response")
                return null
            }

            FilebaseGatewayUris.buildLeafImageUri(leafCid)
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading IPFS file", e)
            null
        }
    }

    /**
     * Upload a single proof-set member straight from its source.
     *
     * The artifact is streamed to the socket and hashed by streaming, so the media leaf never
     * needs a heap buffer or a spooled copy of itself.
     */
    open fun saveArtifact(hash: String, artifact: DeferredArtifact, listener: StorageListener?) {
        uploadSource(hash, artifact.identifier, artifact.length, listener) { artifact.openStream() }
    }

    private fun uploadFile(hash: String, identifier: String, file: File, listener: StorageListener?) {
        uploadSource(hash, identifier, file.length(), listener) { file.inputStream() }
    }

    private fun uploadSource(
        hash: String,
        identifier: String,
        length: Long,
        listener: StorageListener?,
        open: () -> InputStream,
    ) {
        try {
            val objectKey = "$hash/$identifier"
            val contentType = ProofSetContentTypes.contentTypeFor(identifier)

            val requestBody = StreamRequestBody(contentType.toMediaType(), length, open)
            val timestamp = getTimestamp()
            val dateStamp = getDateStamp()
            // Digest the source once, not once per use: SigV4 needs it in the canonical headers,
            // the canonical request, and the sent header.
            val payloadHash = streamingSha256(open)

            // Create canonical request
            val canonicalHeaders = "host:${endpoint.removePrefix("https://")}\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$timestamp\n"

            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "PUT\n" +
                    "/$bucketName/$objectKey\n" +
                    "\n" +
                    canonicalHeaders +
                    "\n" +
                    signedHeaders +
                    "\n" +
                    payloadHash

            // Create string to sign
            val credentialScope = "$dateStamp/$region/$SERVICE/aws4_request"
            val stringToSign = "$ALGORITHM\n" +
                    timestamp + "\n" +
                    credentialScope + "\n" +
                    sha256(canonicalRequest)

            // Calculate signature
            val signature = calculateSignature(secretKey, dateStamp, region, SERVICE, stringToSign)

            // Create authorization header
            val authorization = "$ALGORITHM Credential=$accessKey/$credentialScope, " +
                    "SignedHeaders=$signedHeaders, Signature=$signature"

            val request = Request.Builder()
                .url("$endpoint/$bucketName/$objectKey")
                .put(requestBody)
                .addHeader("Host", endpoint.removePrefix("https://"))
                .addHeader("x-amz-content-sha256", payloadHash)
                .addHeader("x-amz-date", timestamp)
                .addHeader("Authorization", authorization)
                .addHeader("Content-Type", contentType)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully uploaded $identifier to Filebase")
                    // Extract IPFS CID from response headers if available
                    val ipfsCid = response.header("x-amz-meta-cid")
                    Log.d(TAG, "IPFS CID: $ipfsCid")
                    listener?.saveSuccessful(hash, "https://ipfs.filebase.io/ipfs/$ipfsCid")
                } else {
                    val error = IOException("Upload failed: ${response.code} ${response.message}")
                    Log.e(TAG, "Upload failed", error)
                    listener?.saveFailed(error)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file to Filebase", e)
            listener?.saveFailed(e)
        }
    }

    private fun getTimestamp(): String {
        val sdf = SimpleDateFormat(DATE_FORMAT, Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun getDateStamp(): String {
        val sdf = SimpleDateFormat(DATE_STAMP_FORMAT, Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /** SigV4 payload digest over a source that may be far larger than the heap. */
    private fun streamingSha256(open: () -> InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(data: String): String {
        return sha256(data.toByteArray())
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun calculateSignature(key: String, dateStamp: String, regionName: String, serviceName: String, stringToSign: String): String {
        val kDate = hmacSha256(("AWS4" + key).toByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        val kSigning = hmacSha256(kService, "aws4_request")
        val signature = hmacSha256(kSigning, stringToSign)
        return signature.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val algorithm = "HmacSHA256"
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data.toByteArray())
    }

    // The following methods are not implemented for remote storage
    // They would require downloading files from Filebase
    override fun getInputStream(hash: String, identifier: String): InputStream? {

        return null
    }

    override fun proofExists(hash: String): Boolean {
        Log.w(TAG, "proofExists not implemented for FilebaseStorageProvider")
        return false
    }

    override fun proofIdentifierExists(hash: String, identifier: String): Boolean {
        Log.w(TAG, "proofIdentifierExists not implemented for FilebaseStorageProvider")
        return false
    }

    override fun getProofSet(hash: String): ArrayList<Uri> {
        Log.w(TAG, "getProofSet not implemented for FilebaseStorageProvider")
        return ArrayList()
    }

    override fun getProofItem(uri: Uri): InputStream? {
        Log.w(TAG, "getProofItem not implemented for FilebaseStorageProvider")
        return null
    }

    fun testConnection(callback: TestConnectionCallback) {
        Thread {
                    try {
                        val timestamp = getTimestamp()
                        val dateStamp = getDateStamp()

                        // Create canonical request for a simple HEAD request to the bucket
                        val canonicalHeaders =
                                "host:${endpoint.removePrefix("https://")}\n" +
                                        "x-amz-date:$timestamp\n"

                        val signedHeaders = "host;x-amz-date"
                        val canonicalRequest =
                                "HEAD\n" +
                                        "/$bucketName/\n" +
                                        "\n" +
                                        canonicalHeaders +
                                        "\n" +
                                        signedHeaders +
                                        "\n" +
                                        "UNSIGNED-PAYLOAD"

                        // Create string to sign
                        val credentialScope = "$dateStamp/$region/$SERVICE/aws4_request"
                        val stringToSign =
                                "$ALGORITHM\n" +
                                        timestamp +
                                        "\n" +
                                        credentialScope +
                                        "\n" +
                                        sha256(canonicalRequest)

                        // Calculate signature
                        val signature =
                                calculateSignature(
                                        secretKey,
                                        dateStamp,
                                        region,
                                        SERVICE,
                                        stringToSign
                                )

                        // Create authorization header
                        val authorization =
                                "$ALGORITHM Credential=$accessKey/$credentialScope, " +
                                        "SignedHeaders=$signedHeaders, Signature=$signature"

                        val request =
                                Request.Builder()
                                        .url("$endpoint/$bucketName/")
                                        .head()
                                        .addHeader("Host", endpoint.removePrefix("https://"))
                                        .addHeader("x-amz-date", timestamp)
                                        .addHeader("Authorization", authorization)
                                        .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Log.d(TAG, "Successfully connected to Filebase")
                                callback.onTestSuccess()
                            } else if (response.code == 403) {
                                val errorMsg =
                                        "Connection failed (${response.code} ${response.message}): Check your credentials"
                                Log.e(TAG, errorMsg)
                                callback.onTestFailure(errorMsg)
                            } else if (response.code == 404) {
                                val errorMsg =
                                        "Connection failed (${response.code} ${response.message}): Bucket not found"
                                Log.e(TAG, errorMsg)
                                callback.onTestFailure(errorMsg)
                            } else {
                                val errorMsg =
                                        "Connection failed: ${response.code} ${response.message}"
                                Log.e(TAG, errorMsg)
                                callback.onTestFailure(errorMsg)
                            }
                        }
                    } catch (e: Exception) {
                        val errorMsg = "Error connecting to Filebase: ${e.message}"
                        Log.e(TAG, errorMsg, e)
                        callback.onTestFailure(errorMsg)
                    }
                }
                .start()
    }

    /**
     * Test IPFS RPC connectivity with the configured bearer token (`POST /api/v0/version`).
     *
     * Filebase documents `/version` as the auth check for RPC tokens. Bucket MFS probes
     * like `/files/stat` return 400 for valid tokens on this gateway, so they are not used.
     */
    fun testIpfsConnection(callback: TestConnectionCallback) {
        Thread {
            try {
                if (ipfsBearerToken.isBlank()) {
                    callback.onTestFailure("IPFS Bearer Token not configured")
                    return@Thread
                }

                val request = Request.Builder()
                    .url("$IPFS_RPC_BASE_URL/api/v0/version")
                    .post(ByteArray(0).toRequestBody(null))
                    .addHeader("Authorization", "Bearer $ipfsBearerToken")
                    .build()

                Log.d(TAG, "Testing IPFS RPC connection...")

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        // Require a real version payload — reject empty/error JSON that some
                        // gateways still return as HTTP 200 for bad credentials.
                        val version =
                            try {
                                JSONObject(responseBody).optString("Version").trim()
                            } catch (_: Exception) {
                                ""
                            }
                        if (version.isEmpty()) {
                            val errorMsg =
                                "IPFS connection failed: Invalid bearer token or unexpected response"
                            Log.e(TAG, "$errorMsg body=$responseBody")
                            callback.onTestFailure(errorMsg)
                        } else {
                            Log.d(TAG, "Successfully connected to Filebase IPFS RPC: $responseBody")
                            callback.onTestSuccess()
                        }
                    } else if (response.code == 401 || response.code == 403) {
                        val errorMsg =
                            "IPFS connection failed (${response.code}): Invalid bearer token"
                        Log.e(TAG, errorMsg)
                        callback.onTestFailure(errorMsg)
                    } else {
                        val errorMsg =
                            "IPFS connection failed: ${response.code} ${response.message}"
                        Log.e(TAG, errorMsg)
                        callback.onTestFailure(errorMsg)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "Error testing IPFS connection: ${e.message}"
                Log.e(TAG, errorMsg, e)
                callback.onTestFailure(errorMsg)
            }
        }.start()
    }
}
