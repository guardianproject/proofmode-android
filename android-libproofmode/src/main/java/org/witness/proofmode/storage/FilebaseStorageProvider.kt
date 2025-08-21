package org.witness.proofmode.storage

import android.net.Uri
import android.util.Log
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

interface TestConnectionCallback {
    fun onTestSuccess()
    fun onTestFailure(error: String)
}

class FilebaseStorageProvider(
    private val accessKey: String,
    private val secretKey: String,
    private val bucketName: String,
    private val endpoint: String = "https://s3.filebase.com"
) : StorageProvider {

    companion object {
        private const val TAG = "FilebaseStorageProvider"
        private const val REGION = "us-east-1"
        private const val SERVICE = "s3"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val DATE_FORMAT = "yyyyMMdd'T'HHmmss'Z'"
        private const val DATE_STAMP_FORMAT = "yyyyMMdd"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {
        try {
            val tempFile = File.createTempFile("filebase_upload", ".tmp")
            tempFile.outputStream().use { output ->
                stream.copyTo(output)
            }

            uploadFile(hash, identifier, tempFile, listener)
            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving stream to Filebase", e)
            listener?.saveFailed(e)
        }
    }

    override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener?) {
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

    private fun uploadFile(hash: String, identifier: String, file: File, listener: StorageListener?) {
        try {
            val objectKey = "$hash/$identifier"
            val contentType = getContentType(identifier)
            
            val requestBody = file.asRequestBody(contentType.toMediaType())
            val timestamp = getTimestamp()
            val dateStamp = getDateStamp()

            // Create canonical request
            val canonicalHeaders = "host:${endpoint.removePrefix("https://")}\n" +
                    "x-amz-content-sha256:${getPayloadHash(file)}\n" +
                    "x-amz-date:$timestamp\n"

            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "PUT\n" +
                    "/$bucketName/$objectKey\n" +
                    "\n" +
                    canonicalHeaders +
                    "\n" +
                    signedHeaders +
                    "\n" +
                    getPayloadHash(file)

            // Create string to sign
            val credentialScope = "$dateStamp/$REGION/$SERVICE/aws4_request"
            val stringToSign = "$ALGORITHM\n" +
                    timestamp + "\n" +
                    credentialScope + "\n" +
                    sha256(canonicalRequest)

            // Calculate signature
            val signature = calculateSignature(secretKey, dateStamp, REGION, SERVICE, stringToSign)

            // Create authorization header
            val authorization = "$ALGORITHM Credential=$accessKey/$credentialScope, " +
                    "SignedHeaders=$signedHeaders, Signature=$signature"

            val request = Request.Builder()
                .url("$endpoint/$bucketName/$objectKey")
                .put(requestBody)
                .addHeader("Host", endpoint.removePrefix("https://"))
                .addHeader("x-amz-content-sha256", getPayloadHash(file))
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

    private fun getContentType(identifier: String): String {
        return when {
            identifier.endsWith(".jpg") || identifier.endsWith(".jpeg") -> "image/jpeg"
            identifier.endsWith(".png") -> "image/png"
            identifier.endsWith(".mp4") -> "video/mp4"
            identifier.endsWith(".csv") -> "text/csv"
            identifier.endsWith(".asc") || identifier.endsWith(".gpg") -> "application/pgp-signature"
            identifier.endsWith(".zip") -> "application/zip"
            identifier.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
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

    private fun getPayloadHash(file: File): String {
        return sha256(file.readBytes())
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
                        val credentialScope = "$dateStamp/$REGION/$SERVICE/aws4_request"
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
                                        REGION,
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
}
