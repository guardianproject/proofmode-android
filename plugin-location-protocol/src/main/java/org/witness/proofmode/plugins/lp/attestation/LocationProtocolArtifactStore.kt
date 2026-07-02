package org.witness.proofmode.plugins.lp.attestation

import android.util.Log
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// FINDING (Phase 4): getProofSet uses unfiltered listFiles — LP artifacts do not require explicit whitelist entry

/**
 * Persists and retrieves Location Protocol attestation JSON artifacts via
 * the existing [StorageProvider] contract.
 *
 * Files are stored under the key `<mediaHash>.lp.json` within the proof
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
        const val LEGACY_SUFFIX = ".lp.json"
    }

    @Throws(LPArtifactStoreException::class)
    suspend fun saveOffchainAttestation(mediaHash: String, attestationJson: String): String {
        return saveAttestationInternal(mediaHash, attestationJson, OFFCHAIN_SUFFIX)
    }

    @Throws(LPArtifactStoreException::class)
    suspend fun saveOnchainAttestation(mediaHash: String, attestationJson: String): String {
        return saveAttestationInternal(mediaHash, attestationJson, ONCHAIN_SUFFIX)
    }

    @Throws(LPArtifactStoreException::class)
    suspend fun savePendingOnchainAttestation(mediaHash: String, attestationJson: String): String {
        return saveAttestationInternal(mediaHash, attestationJson, ONCHAIN_PENDING_SUFFIX)
    }

    private suspend fun saveAttestationInternal(mediaHash: String, attestationJson: String, suffix: String): String {
        val identifier = "$mediaHash$suffix"

        if (storageProvider.proofIdentifierExists(mediaHash, identifier)) {
            return identifier
        }

        return suspendCoroutine { continuation ->
            storageProvider.saveText(
                mediaHash,
                identifier,
                attestationJson,
                object : StorageListener {
                    override fun saveSuccessful(hash: String, uri: String) {
                        continuation.resume(uri.takeIf { it.isNotBlank() } ?: identifier)
                    }

                    override fun saveFailed(exception: Exception) {
                        continuation.resumeWithException(
                            LPArtifactStoreException(
                                "saveAttestation failed for identifier=$identifier",
                                exception
                            )
                        )
                    }
                }
            )
        }
    }

    fun readOffchainAttestation(mediaHash: String): String? {
        val offchain = "$mediaHash$OFFCHAIN_SUFFIX"
        val legacy = "$mediaHash$LEGACY_SUFFIX"
        
        return tryRead(mediaHash, offchain) ?: tryRead(mediaHash, legacy)
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

internal class LPArtifactStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
