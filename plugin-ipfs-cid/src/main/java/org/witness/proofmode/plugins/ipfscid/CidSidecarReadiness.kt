package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.witness.proofmode.ProofMode
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber

data class CidSidecarRefs(
    val rootCid: String,
    val mediaCid: String,
)

fun interface CidSidecarReadiness {
    suspend fun awaitReady(
        context: Context,
        storageProvider: StorageProvider,
        proofSetHash: String,
        mediaMimeType: String?,
    ): CidSidecarRefs?
}

class DefaultCidSidecarReadiness(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val timeoutMsOverride: Long? = null,
) : CidSidecarReadiness {

    override suspend fun awaitReady(
        context: Context,
        storageProvider: StorageProvider,
        proofSetHash: String,
        mediaMimeType: String?,
    ): CidSidecarRefs? {
        if (!LocalIpfsCidGate.isEnabled(context)) {
            Timber.d("CID readiness skipped (gate off) for %s", proofSetHash)
            return null
        }
        val timeoutMs = timeoutMsOverride ?: resolveTimeoutMs(context)
        val deadline = nowMs() + timeoutMs
        while (nowMs() < deadline) {
            val snapshot = loadNormalizedSnapshot(storageProvider, proofSetHash, mediaMimeType)
            if (snapshot != null && isPreferComplete(context, snapshot, proofSetHash)) {
                val refs = toRefs(snapshot, proofSetHash) ?: return null
                Timber.i(
                    "CID readiness complete for %s root=%s",
                    proofSetHash,
                    truncateCid(refs.rootCid),
                )
                return refs
            }
            delayFn(POLL_INTERVAL_MS)
        }
        val finalSnap = loadNormalizedSnapshot(storageProvider, proofSetHash, mediaMimeType)
        if (finalSnap != null && isUsable(finalSnap, proofSetHash)) {
            Timber.w(
                "CID readiness first-fallback (usable, notary-incomplete) for %s",
                proofSetHash,
            )
            return toRefs(finalSnap, proofSetHash)
        }
        Timber.w("CID readiness last-fallback (null) for %s", proofSetHash)
        return null
    }

    companion object {
        const val POLL_INTERVAL_MS = 500L
        const val TIMEOUT_NO_NOTARY_MS = 30_000L
        const val TIMEOUT_NOTARY_MS = 90_000L
        private const val CID_LOG_CHARS = 16

        fun resolveTimeoutMs(context: Context): Long {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val ots = prefs.getBoolean(
                ProofMode.PREF_OPTION_NOTARY_OTS,
                ProofMode.PREF_OPTION_NOTARY_OTS_DEFAULT,
            )
            val nostr = prefs.getBoolean(
                ProofMode.PREF_OPTION_NOTARY_NOSTR,
                ProofMode.PREF_OPTION_NOTARY_NOSTR_DEFAULT,
            )
            return if (ots || nostr) TIMEOUT_NOTARY_MS else TIMEOUT_NO_NOTARY_MS
        }

        fun isUsable(snapshot: SidecarSnapshot, proofSetHash: String): Boolean {
            if (snapshot.rootCid.isBlank()) return false
            return MediaLinkNaming.findCachedMediaLeaf(snapshot, proofSetHash) != null
        }

        fun isPreferComplete(
            context: Context,
            snapshot: SidecarSnapshot,
            proofSetHash: String,
        ): Boolean {
            if (!isUsable(snapshot, proofSetHash)) return false
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val otsOn = prefs.getBoolean(
                ProofMode.PREF_OPTION_NOTARY_OTS,
                ProofMode.PREF_OPTION_NOTARY_OTS_DEFAULT,
            )
            val nostrOn = prefs.getBoolean(
                ProofMode.PREF_OPTION_NOTARY_NOSTR,
                ProofMode.PREF_OPTION_NOTARY_NOSTR_DEFAULT,
            )
            if (otsOn && !snapshot.files.containsKey("$proofSetHash.ots")) return false
            if (nostrOn && !snapshot.files.containsKey("$proofSetHash.nostr")) return false
            return true
        }

        fun loadNormalizedSnapshot(
            storageProvider: StorageProvider,
            proofSetHash: String,
            mediaMimeType: String?,
        ): SidecarSnapshot? {
            return try {
                val stream = storageProvider.getInputStream(
                    proofSetHash,
                    IpfsCidSidecar.sidecarBasename(proofSetHash),
                ) ?: return null
                stream.use { input ->
                    val bytes = input.readBytes()
                    SidecarReader.decodeAndNormalize(bytes, proofSetHash, mediaMimeType)
                }
            } catch (e: Exception) {
                // Decode/IO → null per poll; never swallow structured cancel (CE is an Exception).
                if (e is CancellationException) throw e
                Timber.d(e, "CID readiness: snapshot load failed for %s", proofSetHash)
                null
            }
        }

        fun toRefs(snapshot: SidecarSnapshot, proofSetHash: String): CidSidecarRefs? {
            val leaf = MediaLinkNaming.findCachedMediaLeaf(snapshot, proofSetHash) ?: run {
                Timber.w("CID readiness missing media leaf for %s", proofSetHash)
                return null
            }
            return CidSidecarRefs(rootCid = snapshot.rootCid, mediaCid = leaf.leafCid)
        }

        fun truncateCid(cid: String): String =
            if (cid.length <= CID_LOG_CHARS) cid else cid.take(CID_LOG_CHARS)
    }
}
