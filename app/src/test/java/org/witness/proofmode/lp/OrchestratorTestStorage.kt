package org.witness.proofmode.lp

import android.net.Uri
import org.json.JSONObject
import org.witness.proofmode.ProofMode
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [StorageProvider] for orchestrator unit/integration tests.
 */
class OrchestratorTestStorage : StorageProvider {

    private val proofHashes = ConcurrentHashMap.newKeySet<String>()
    private val store = ConcurrentHashMap<String, ByteArray>()

    fun seedProof(
        hash: String,
        latitude: Double = 37.77,
        longitude: Double = -122.42,
    ) {
        proofHashes.add(hash)
        val json = JSONObject().apply {
            put(ProofModeV1Constants.LOCATION_LATITUDE, latitude.toString())
            put(ProofModeV1Constants.LOCATION_LONGITUDE, longitude.toString())
        }
        store["$hash/${hash}${ProofMode.PROOF_FILE_JSON_TAG}"] =
            json.toString().toByteArray(Charsets.UTF_8)
    }

    fun seedArtifactIdentifier(hash: String, identifier: String, content: String = "{}") {
        proofHashes.add(hash)
        store["$hash/$identifier"] = content.toByteArray(Charsets.UTF_8)
    }

    /** Seeds [hash] on the first [proofExists] poll — simulates delayed sidecar availability. */
    fun deferSeedOnFirstPoll(
        hash: String,
        latitude: Double = 37.77,
        longitude: Double = -122.42,
    ) {
        deferredSeeds[hash] = Pair(latitude, longitude)
    }

    private val deferredSeeds = ConcurrentHashMap<String, Pair<Double, Double>>()

    private fun maybeDeferSeed(hash: String) {
        val coords = deferredSeeds.remove(hash) ?: return
        seedProof(hash, coords.first, coords.second)
    }

    override fun saveStream(
        hash: String,
        identifier: String,
        stream: InputStream,
        listener: StorageListener,
    ) {
        listener.saveSuccessful(hash, identifier)
    }

    override fun saveBytes(
        hash: String,
        identifier: String,
        data: ByteArray,
        listener: StorageListener,
    ) {
        store["$hash/$identifier"] = data
        listener.saveSuccessful(hash, identifier)
    }

    override fun saveText(
        hash: String,
        identifier: String,
        data: String,
        listener: StorageListener,
    ) {
        store["$hash/$identifier"] = data.toByteArray(Charsets.UTF_8)
        listener.saveSuccessful(hash, identifier)
    }

    override fun getInputStream(hash: String, identifier: String): InputStream? {
        val bytes = store["$hash/$identifier"] ?: return null
        return bytes.inputStream()
    }

    override fun proofExists(hash: String): Boolean {
        maybeDeferSeed(hash)
        return proofHashes.contains(hash)
    }

    override fun proofIdentifierExists(hash: String, identifier: String): Boolean =
        store.containsKey("$hash/$identifier")

    override fun getProofSet(hash: String): ArrayList<Uri> = ArrayList()

    override fun getProofItem(uri: Uri): InputStream? = null
}
