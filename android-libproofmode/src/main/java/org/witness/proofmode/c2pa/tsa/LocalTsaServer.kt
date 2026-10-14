package org.witness.proofmode.c2pa.tsa

import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RFC 3161 "Time-Stamp Protocol via HTTP" (§3.4) on the loopback interface: a POST
 * whose body is a DER `TimeStampReq` gets back a DER `TimeStampResp` as
 * `application/timestamp-reply`, produced by [handler]: the on-device
 * [TimeStampResponder], or a [TsaForwarder] relaying to a remote TSA.
 *
 * With [requiredPath] null any request path is accepted, matching public TSAs.
 * Otherwise only that path is served (404 for anything else); an unguessable path
 * keeps other apps on the device, which share loopback, from using the server.
 *
 * Deliberately a minimal HTTP/1.1 implementation — one request per connection,
 * `Content-Length` or chunked bodies — because the only client is c2pa-rs signing
 * through [org.contentauth.c2pa.Signer]'s `tsaURL`, and it saves pulling an HTTP
 * server library into the app.
 *
 * Bound to 127.0.0.1 only, but note that loopback on Android is shared by every app
 * on the device: without [requiredPath], anything local can obtain tokens while it runs.
 */
class LocalTsaServer(
    private val handler: TsaHandler,
    requestedPort: Int = 0,
    private val requiredPath: String? = null,
) {
    companion object {
        private const val MAX_HEADER_BYTES = 8 * 1024

        /** A TimeStampReq is a hash plus a few small fields; anything this big is not one. */
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val SOCKET_TIMEOUT_MS = 10_000
    }

    // Explicitly IPv4: Android's InetAddress.getLoopbackAddress() is ::1 (the JDK's is
    // 127.0.0.1), and c2pa-rs is pointed at 127.0.0.1, so binding "the" loopback there
    // left the server unreachable.
    private val serverSocket = ServerSocket(requestedPort, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val workers: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "LocalTsa-worker").apply { isDaemon = true }
    }

    /** The actual bound port; differs from the requested one when that was 0. */
    val port: Int get() = serverSocket.localPort

    /** Numeric, matching the bind: "localhost" may resolve to ::1 first, which we don't bind. */
    val url: String get() = "http://127.0.0.1:$port${requiredPath ?: "/"}"

    val isRunning: Boolean get() = !serverSocket.isClosed

    init {
        Thread(::acceptLoop, "LocalTsa-accept").apply { isDaemon = true }.start()
        // Not the url: when requiredPath is a secret, it must not reach the log.
        Timber.i("LocalTsa: listening on 127.0.0.1:%d (%s)", port, handler.javaClass.simpleName)
    }

    fun close() {
        try {
            serverSocket.close()
        } catch (_: IOException) {
        }
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: SocketException) {
                break // closed
            } catch (e: IOException) {
                Timber.w(e, "LocalTsa: accept failed")
                continue
            }
            workers.execute { socket.use(::handle) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val head = readHead(input) ?: return writeStatus(output, 400, "Bad Request")
            val lines = head.split("\r\n")
            val requestLine = lines.first().split(' ')
            if (requestLine.size < 3) return writeStatus(output, 400, "Bad Request")
            if (requestLine[0] != "POST") return writeStatus(output, 405, "Method Not Allowed", "Allow: POST\r\n")
            if (requiredPath != null && requestLine[1] != requiredPath) return writeStatus(output, 404, "Not Found")

            val headers = lines.drop(1)
                .mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }
                .toMap()

            val contentType = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
            if (contentType != TimeStampResponder.CONTENT_TYPE_QUERY) {
                return writeStatus(output, 415, "Unsupported Media Type")
            }

            val body = when {
                headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input)
                else -> headers["content-length"]?.toIntOrNull()?.let { readFixed(input, it) }
            } ?: return writeStatus(output, 400, "Bad Request")

            val reply = try {
                handler.handle(body)
            } catch (e: IOException) {
                Timber.w(e, "LocalTsa: no reply from %s", handler.javaClass.simpleName)
                return writeStatus(output, 502, "Bad Gateway")
            }
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: ${TimeStampResponder.CONTENT_TYPE_REPLY}\r\n" +
                    "Content-Length: ${reply.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            output.write(reply)
            output.flush()
        } catch (e: IOException) {
            Timber.w(e, "LocalTsa: request failed")
        }
    }

    /** Request line + headers, up to (not including) the blank line; null if oversized or truncated. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0 // progress through "\r\n\r\n"
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || buf.size() >= MAX_HEADER_BYTES) return null
            buf.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return buf.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
    }

    private fun readFixed(input: InputStream, length: Int): ByteArray? {
        if (length < 0 || length > MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return body
    }

    private fun readChunked(input: InputStream): ByteArray? {
        val body = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (size == 0) {
                // Skip any trailers up to the terminating blank line.
                while (true) {
                    val trailer = readLine(input) ?: return null
                    if (trailer.isEmpty()) return body.toByteArray()
                }
            }
            if (body.size() + size > MAX_BODY_BYTES) return null
            body.write(readFixed(input, size) ?: return null)
            if (readLine(input)?.isEmpty() != true) return null // CRLF after chunk data
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || buf.length > MAX_HEADER_BYTES) return null
            if (b == '\n'.code) return buf.toString().removeSuffix("\r")
            buf.append(b.toChar())
        }
    }

    private fun writeStatus(output: OutputStream, code: Int, reason: String, extraHeaders: String = "") {
        output.write(
            "HTTP/1.1 $code $reason\r\n${extraHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        output.flush()
    }
}
