package org.witness.proofmode.c2pa.proofsign

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-use capture-authorization nonces.
 *
 * Defense against a Frida-driven signing-oracle attack: an attacker who
 * reflectively instantiates ProofSignClient and calls
 * signC2PAClaimWithDeviceAuth() to obtain valid C2PA signatures for arbitrary
 * media the app never captured. Play Integrity / Key Attestation only prove
 * the device is genuine and the app is installed; they do not prove that the
 * media being signed corresponds to a real camera capture event in this app.
 *
 * The camera capture pipeline issues a nonce bound to the SHA-256 of the
 * captured file at the moment the file is saved to disk. The signing pipeline
 * must consume that nonce, re-hashing the file it is about to sign and
 * verifying the digest matches what was issued. A nonce is single-use and
 * expires; an attacker who has not driven the camera capture path cannot
 * produce one.
 *
 * Binding to the file hash (rather than to bytes or a URI) means an attacker
 * who swaps file contents between capture and sign sees the digest diverge
 * and the gate close — without forcing us to hold large videos in memory.
 */
object CaptureAuthority {

    private const val TAG = "CaptureAuthority"
    private const val NONCE_LEN = 32
    private const val NONCE_TTL_MS = 60_000L
    private const val MAX_OUTSTANDING = 16

    private data class Entry(
        val nonce: ByteArray,
        val fileDigest: ByteArray,
        val issuedAtMs: Long,
    )

    private val rng = SecureRandom()
    private val outstanding = ConcurrentHashMap<String, Entry>()
    private val issuedCount = AtomicLong(0)

    /**
     * Issue a single-use nonce bound to a captured file's SHA-256 digest.
     * Called from the camera capture pipeline immediately after the file is
     * written to disk.
     */
    fun issueNonce(fileDigest: ByteArray): ByteArray {
        require(fileDigest.size == 32) { "fileDigest must be SHA-256 (32 bytes)" }
        reapExpired()

        if (outstanding.size >= MAX_OUTSTANDING) {
            outstanding.entries
                .minByOrNull { it.value.issuedAtMs }
                ?.let { outstanding.remove(it.key) }
        }

        val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
        outstanding[hex(nonce)] = Entry(nonce, fileDigest.copyOf(), System.currentTimeMillis())
        issuedCount.incrementAndGet()
        return nonce
    }

    /**
     * Validate and consume a nonce against the digest of the file actually
     * being signed. Returns true iff the nonce is valid, unexpired, and the
     * supplied digest matches the one bound at issue time. The nonce is
     * consumed (removed) on every call, success or failure.
     */
    fun consumeNonce(nonce: ByteArray, fileDigest: ByteArray): Boolean {
        if (nonce.size != NONCE_LEN) return false
        if (fileDigest.size != 32) return false

        val entry = outstanding.remove(hex(nonce)) ?: return false

        val ageMs = System.currentTimeMillis() - entry.issuedAtMs
        if (ageMs > NONCE_TTL_MS) {
            Log.w(TAG, "nonce expired (age=${ageMs}ms)")
            return false
        }

        if (!MessageDigest.isEqual(fileDigest, entry.fileDigest)) {
            Log.w(TAG, "nonce/file digest mismatch — content swap or wrong file")
            return false
        }

        return MessageDigest.isEqual(nonce, entry.nonce)
    }

    fun statsSnapshot(): Map<String, Long> = mapOf(
        "outstanding" to outstanding.size.toLong(),
        "issued_total" to issuedCount.get(),
    )

    /**
     * Per-thread authorization scope, set after a nonce is successfully
     * consumed and held only for the duration of the actual signing call.
     *
     * This is the second layer of defense: even if an attacker reaches the
     * inner signing methods directly (Frida's typical
     * `Java.use('...ProofSignClient').signC2PAClaimWithDeviceAuth(...)`),
     * those methods refuse to proceed unless [currentAuthorizedDigest]
     * returns non-null on the calling thread. The only way to populate it
     * is through [enterSigningScope], which is only called by the
     * in-tree signing path after a valid nonce.
     */
    private val activeDigest = ThreadLocal<ByteArray?>()

    /**
     * Run [block] with the calling thread marked as authorized to sign
     * content whose digest is [fileDigest]. The scope is torn down in a
     * finally so an exception in [block] does not leave the thread
     * permanently authorized.
     */
    inline fun <T> enterSigningScope(fileDigest: ByteArray, block: () -> T): T {
        val previous = activeDigestThreadLocal.get()
        activeDigestThreadLocal.set(fileDigest)
        try {
            return block()
        } finally {
            if (previous == null) activeDigestThreadLocal.remove()
            else activeDigestThreadLocal.set(previous)
        }
    }

    @PublishedApi
    internal val activeDigestThreadLocal: ThreadLocal<ByteArray?> get() = activeDigest

    /**
     * Returns the digest of the file the calling thread is currently
     * authorized to sign, or null if no signing scope is active.
     * Inner signing methods consult this and refuse if it is null.
     */
    fun currentAuthorizedDigest(): ByteArray? = activeDigest.get()

    private fun reapExpired() {
        val cutoff = System.currentTimeMillis() - NONCE_TTL_MS
        outstanding.entries.removeAll { it.value.issuedAtMs < cutoff }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("0123456789abcdef"[(b.toInt() ushr 4) and 0xF])
            sb.append("0123456789abcdef"[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}

/** Thrown when a signing request lacks a valid capture-authorization nonce. */
class UnauthorizedCaptureException(message: String) : SecurityException(message)
