package org.witness.proofmode.plugins.lp.attestation

import android.util.Log
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Persists and retrieves Location Protocol attestation JSON artifacts via
 * the existing [StorageProvider] contract.
 *
 * Files are stored under the keys `<mediaHash>.lp.offchain.json`, `.lp.onchain.json` and
 * `.lp.onchain.pending.json` within the proof
 * storage bucket already owned by the calling media asset.
 *
 * LP artifacts are written into the same directory scanned by
 * DefaultStorageProvider.getProofSet(). No extension whitelist exists; files
 * are automatically included in proof-set enumeration.
 *
 * No new storage backends are registered; [StorageProvider] is injected by
 * the coordinator from the app-level [DefaultStorageProvider].
 */
class LocationProtocolArtifactStore(private val storageProvider: StorageProvider) {

    companion object {
        const val OFFCHAIN_SUFFIX = ".lp.offchain.json"
        const val ONCHAIN_SUFFIX = ".lp.onchain.json"
        const val ONCHAIN_PENDING_SUFFIX = ".lp.onchain.pending.json"
    }

    suspend fun saveOffchainAttestation(mediaHash: String, attestationJson: String) {
        saveAttestationInternal(mediaHash, attestationJson, OFFCHAIN_SUFFIX)
    }

    /** Saves the confirmed record, then deletes the pending record it supersedes. */
    suspend fun saveOnchainAttestation(mediaHash: String, attestationJson: String) {
        saveAttestationInternal(mediaHash, attestationJson, ONCHAIN_SUFFIX)
        // Runs on the already-saved early return too: a re-check and the live confirmation can
        // both confirm one transaction, and whichever saves second still clears the pending record.
        deletePendingOnchainAttestation(mediaHash)
    }

    suspend fun savePendingOnchainAttestation(mediaHash: String, attestationJson: String) {
        saveAttestationInternal(mediaHash, attestationJson, ONCHAIN_PENDING_SUFFIX)
    }

    private suspend fun saveAttestationInternal(mediaHash: String, attestationJson: String, suffix: String) {
        val identifier = "$mediaHash$suffix"

        if (storageProvider.proofIdentifierExists(mediaHash, identifier)) {
            return
        }

        suspendCoroutine<Unit> { continuation ->
            storageProvider.saveText(
                mediaHash,
                identifier,
                attestationJson,
                object : StorageListener {
                    override fun saveSuccessful(hash: String, uri: String) {
                        continuation.resume(Unit)
                    }

                    override fun saveFailed(exception: Exception) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }
    }

    /**
     * [StorageProvider] has no delete, so this resolves the pending record's `file://` URI from
     * [StorageProvider.getProofSet] and deletes the file. Absent is a logged no-op.
     */
    fun deletePendingOnchainAttestation(mediaHash: String) {
        val identifier = "$mediaHash$ONCHAIN_PENDING_SUFFIX"
        val uri = storageProvider.getProofSet(mediaHash).orEmpty()
            .firstOrNull { it.lastPathSegment == identifier }
        if (uri == null) {
            Log.d("LocationProtocolArtifactStore", "deletePending: no pending record identifier=$identifier")
            return
        }
        if (!File(uri.path.orEmpty()).delete()) {
            Log.w("LocationProtocolArtifactStore", "deletePending: delete failed identifier=$identifier")
        }
    }

    fun readOffchainAttestation(mediaHash: String): String? {
        return tryRead(mediaHash, "$mediaHash$OFFCHAIN_SUFFIX")
    }

    fun readOnchainAttestation(mediaHash: String): String? {
        return tryRead(mediaHash, "$mediaHash$ONCHAIN_SUFFIX")
    }

    fun readPendingOnchainAttestation(mediaHash: String): String? {
        return tryRead(mediaHash, "$mediaHash$ONCHAIN_PENDING_SUFFIX")
    }

    private fun tryRead(mediaHash: String, identifier: String): String? {
        return try {
            storageProvider.getInputStream(mediaHash, identifier)
                ?.use { it.bufferedReader().readText() }
        } catch (e: IOException) {
            Log.w("LocationProtocolArtifactStore", "readAttestation failed: $e")
            null
        }
    }
}
