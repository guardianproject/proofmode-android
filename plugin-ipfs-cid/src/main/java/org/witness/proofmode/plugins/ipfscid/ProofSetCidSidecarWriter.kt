package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import org.witness.proofmode.cid.CidLib
import org.witness.proofmode.plugin.ProofWriteEvent
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService

object ProofSetCidSidecarWriter {
    private val scheduler = CidWorkScheduler()

    fun scheduleInitialSidecarWrite(event: ProofWriteEvent, executor: ExecutorService) {
        if (!LocalIpfsCidGate.isEnabled(event.context)) return
        val storageProvider = event.storageProvider
        val proofSetHash = event.mediaHash
        scheduler.enqueueCoalescedWork(
            proofSetHash = proofSetHash,
            executor = executor,
            work = { writeInitialCidSidecar(event) },
            onPendingRefresh = { hash ->
                scheduleCidSidecarRefresh(hash, storageProvider, executor, event.context)
            },
        )
    }

    fun scheduleCidSidecarRefresh(
        proofSetHash: String,
        storageProvider: StorageProvider,
        executor: ExecutorService,
        context: Context,
    ) {
        if (!LocalIpfsCidGate.isEnabled(context)) return
        scheduler.enqueueCoalescedWork(
            proofSetHash = proofSetHash,
            executor = executor,
            work = { refreshCidSidecar(context, proofSetHash, storageProvider, executor) },
            onPendingRefresh = { hash ->
                scheduleCidSidecarRefresh(hash, storageProvider, executor, context)
            },
        )
    }

    internal fun writeInitialCidSidecar(event: ProofWriteEvent): CidSidecarWriteOutcome {
        val proofSetHash = event.mediaHash
        if (!LocalIpfsCidGate.isEnabled(event.context)) {
            return CidSidecarWriteOutcome.SkippedGateOff.also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        }
        val storageProvider = event.storageProvider
        val onDiskIds = basenamesFromProofSet(storageProvider, proofSetHash)
        val mediaBytes = try {
            event.context.contentResolver.openInputStream(event.mediaUri)?.use { it.readBytes() }
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "initial sidecar write OOM reading media for %s", proofSetHash)
            return CidSidecarWriteOutcome.Failed("OOM reading media").also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        } ?: run {
            Timber.w("initial sidecar write: no media bytes for %s", proofSetHash)
            return CidSidecarWriteOutcome.Failed("no media bytes").also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        }

        val mimeType = event.context.contentResolver.getType(event.mediaUri)
        val manifestMembers = ProofSetCidMembershipPolicy.manifestMemberBasenames(
            event.context, proofSetHash, onDiskIds,
        )
        val diskBytes = readBytesForIdentifiers(storageProvider, proofSetHash, manifestMembers)
        val entries = ProofSetCidManifest.composeByteBackedManifest(
            proofSetHash = proofSetHash,
            manifestMemberBasenames = manifestMembers,
            diskBytesByBasename = diskBytes,
            mediaBytes = mediaBytes,
            mediaMimeType = mimeType,
            includeOts = false,
            includeNostr = false,
        )
        if (entries.isEmpty()) {
            return CidSidecarWriteOutcome.SkippedEmptyManifest.also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        }

        val result = try {
            CidLib.computeProofSetCid(entries)
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "initial sidecar write OOM for %s", proofSetHash)
            return CidSidecarWriteOutcome.Failed("OOM").also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        } catch (e: Exception) {
            Timber.e(e, "initial sidecar write failed for %s", proofSetHash)
            return CidSidecarWriteOutcome.Failed(e.message ?: "unknown").also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        }

        writeCidSidecar(storageProvider, proofSetHash, result.rootCid, result.files, result.tsizes)
        return CidSidecarWriteOutcome.Success(result.rootCid).also {
            CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
        }
    }

    internal fun refreshCidSidecar(
        context: Context,
        proofSetHash: String,
        storageProvider: StorageProvider,
        executor: ExecutorService,
    ): CidSidecarWriteOutcome {
        if (!LocalIpfsCidGate.isEnabled(context)) {
            return CidSidecarWriteOutcome.SkippedGateOff.also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        }
        val sidecarId = IpfsCidSidecar.sidecarBasename(proofSetHash)
        val sidecarBytes = storageProvider.getInputStream(proofSetHash, sidecarId)?.use { it.readBytes() }
        if (sidecarBytes == null) {
            // No self-reschedule: markPending + this job's finally would busy-loop.
            // F12 coalesce already defers refresh until initial write completes.
            Timber.w(
                "CID sidecar refresh: no sidecar for %s — skipping until initial write",
                proofSetHash,
            )
            return CidSidecarWriteOutcome.Failed("no sidecar yet").also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        }
        val mimeType = inferMediaMimeType(storageProvider, proofSetHash)
        val sidecar = SidecarReader.decodeAndNormalize(sidecarBytes, proofSetHash, mimeType)
        val onDiskIds = basenamesFromProofSet(storageProvider, proofSetHash)
        val manifestMembers = ProofSetCidMembershipPolicy.manifestMemberBasenames(
            context, proofSetHash, onDiskIds,
        )
        val newLeafBytes = mutableMapOf<String, ByteArray>()
        for (basename in manifestMembers) {
            if (sidecar.files.containsKey(basename)) continue
            val bytes = storageProvider.getInputStream(proofSetHash, basename)?.use { it.readBytes() }
            if (bytes != null) newLeafBytes[basename] = bytes
        }
        val leaves = ProofSetCidManifest.composeLeafBackedManifest(
            proofSetHash = proofSetHash,
            manifestMemberBasenames = manifestMembers,
            sidecar = sidecar,
            newLeafBytesByBasename = newLeafBytes,
            computeLeafFromBytes = { bytes ->
                val leaf = CidLib.computeFileLeafCidAndTsize(bytes)
                leaf.leafCid to leaf.tsize
            },
        )
        if (leaves.isEmpty()) {
            return CidSidecarWriteOutcome.SkippedEmptyManifest.also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        }

        val result = try {
            CidLib.computeProofSetCidFromLeaves(leaves)
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "CID sidecar refresh OOM for %s", proofSetHash)
            return CidSidecarWriteOutcome.Failed("OOM").also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        } catch (e: Exception) {
            Timber.e(e, "CID sidecar refresh failed for %s", proofSetHash)
            return CidSidecarWriteOutcome.Failed(e.message ?: "unknown").also {
                CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
            }
        }

        writeCidSidecar(storageProvider, proofSetHash, result.rootCid, result.files, result.tsizes)
        return CidSidecarWriteOutcome.Success(result.rootCid).also {
            CidSidecarWriteOutcome.logAtBoundary(proofSetHash, it)
        }
    }

    private fun inferMediaMimeType(storageProvider: StorageProvider, proofSetHash: String): String? {
        val uris = storageProvider.getProofSet(proofSetHash) ?: return null
        for (uri in uris) {
            val name = uri.path?.let { File(it).name } ?: continue
            if (!name.startsWith("$proofSetHash.")) continue
            if (ProofSetCidMembershipPolicy.isExcludedFromManifest(name)) continue
            if (ProofSetCidMembershipPolicy.isCoreArtifactForTest(proofSetHash, name)) continue
            if (name.endsWith(".ots") || name.endsWith(".nostr")) continue
            return when {
                name.endsWith(".jpg") -> "image/jpeg"
                name.endsWith(".png") -> "image/png"
                name.endsWith(".mp4") -> "video/mp4"
                else -> null
            }
        }
        return null
    }

    private fun basenamesFromProofSet(storageProvider: StorageProvider, proofSetHash: String): List<String> {
        val uris = storageProvider.getProofSet(proofSetHash) ?: return emptyList()
        return uris.mapNotNull { uri ->
            val path = uri.path ?: return@mapNotNull null
            File(path).name
        }
    }

    private fun readBytesForIdentifiers(
        storageProvider: StorageProvider,
        proofSetHash: String,
        identifiers: Collection<String>,
    ): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        for (id in identifiers) {
            val bytes = storageProvider.getInputStream(proofSetHash, id)?.use { it.readBytes() }
            if (bytes != null) out[id] = bytes
        }
        return out
    }

    private fun writeCidSidecar(
        storageProvider: StorageProvider,
        proofSetHash: String,
        rootCid: String,
        files: Map<String, String>,
        tsizes: Map<String, Long>,
    ) {
        val jsonBytes = IpfsCidSidecar.encode(
            rootCid = rootCid,
            files = files,
            tsizes = tsizes,
            computedAtMs = System.currentTimeMillis(),
        )
        storageProvider.saveBytes(proofSetHash, IpfsCidSidecar.sidecarBasename(proofSetHash), jsonBytes, null)
    }

    internal fun resetSidecarWriterTestState() {
        scheduler.resetSchedulerForTests()
    }
}
