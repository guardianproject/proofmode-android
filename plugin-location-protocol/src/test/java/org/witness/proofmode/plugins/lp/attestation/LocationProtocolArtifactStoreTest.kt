package org.witness.proofmode.plugins.lp.attestation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList

/**
 * Unit tests for [LocationProtocolArtifactStore].
 *
 * Uses [FakeStorageProvider] — an in-memory StorageProvider that records call counts
 * so the idempotency contract can be verified.
 */
class LocationProtocolArtifactStoreTest {

    private lateinit var fakeStorage: FakeStorageProvider
    private lateinit var store: LocationProtocolArtifactStore

    @Before
    fun setUp() {
        fakeStorage = FakeStorageProvider()
        store = LocationProtocolArtifactStore(fakeStorage)
    }

    @Test
    fun `saveOffchainAttestation is idempotent — second call does not write again`() {
        val hash = "hash1"
        val json = """{"type":"test"}"""

        val first = kotlinx.coroutines.runBlocking {
            store.saveOffchainAttestation(hash, json)
        }
        val second = kotlinx.coroutines.runBlocking {
            store.saveOffchainAttestation(hash, json)
        }

        assertEquals("saveText should be called exactly once (idempotency)", 1, fakeStorage.saveTextCallCount)
        assertEquals("hash1.lp.offchain.json", first)
        assertEquals("hash1.lp.offchain.json", second)
    }

    @Test(expected = LPArtifactStoreException::class)
    fun `saveOffchainAttestation failure propagates LPArtifactStoreException`() {
        fakeStorage.saveError = IOException("disk full")

        try {
            kotlinx.coroutines.runBlocking {
                store.saveOffchainAttestation("hash2", "{}")
            }
        } catch (e: LPArtifactStoreException) {
            assertEquals("disk full", e.cause?.message)
            throw e
        }
    }

    @Test
    fun `saveOffchainAttestation returns resolved path from storage listener`() {
        val hash = "hash1"
        val json = """{"type":"test"}"""
        fakeStorage.savedPath = "/data/user/0/org.witness.proofmode/files/proofmode/hash1/hash1.lp.offchain.json"

        val savedPath = kotlinx.coroutines.runBlocking {
            store.saveOffchainAttestation(hash, json)
        }

        assertEquals(fakeStorage.savedPath, savedPath)
    }

    @Test
    fun `saveOffchainAttestation falls back to identifier when listener path is blank`() {
        val hash = "hash1"
        val json = """{"type":"test"}"""
        fakeStorage.savedPath = ""

        val savedPath = kotlinx.coroutines.runBlocking {
            store.saveOffchainAttestation(hash, json)
        }

        assertEquals("hash1.lp.offchain.json", savedPath)
    }

    @Test
    fun `readOffchainAttestation returns stored JSON after saveOffchainAttestation`() {
        val hash = "hash1"
        val json = """{"type":"test","value":42}"""

        kotlinx.coroutines.runBlocking {
            store.saveOffchainAttestation(hash, json)
        }

        val read = store.readOffchainAttestation(hash)
        assertEquals(json, read)
    }

    @Test
    fun `readOffchainAttestation returns null when nothing has been saved`() {
        val result = store.readOffchainAttestation("no-such-hash")
        assertNull(result)
    }

    @Test
    fun `saveOnchainAttestation is idempotent — second call does not write again`() {
        val hash = "hash1"
        val json = """{"txHash":"0xabc"}"""

        val first = kotlinx.coroutines.runBlocking {
            store.saveOnchainAttestation(hash, json)
        }
        val second = kotlinx.coroutines.runBlocking {
            store.saveOnchainAttestation(hash, json)
        }

        assertEquals(1, fakeStorage.saveTextCallCount)
        assertEquals("hash1.lp.onchain.json", first)
        assertEquals("hash1.lp.onchain.json", second)
    }

    @Test
    fun `savePendingOnchainAttestation persists pending suffix artifact`() {
        val hash = "hash1"
        val json = """{"status":"pending_broadcast","txHash":"0xabc"}"""

        val savedPath = kotlinx.coroutines.runBlocking {
            store.savePendingOnchainAttestation(hash, json)
        }

        assertEquals("hash1.lp.onchain.pending.json", savedPath)
        assertEquals(json, store.readPendingOnchainAttestation(hash))
        assertNull(store.readOnchainAttestation(hash))
    }

    @Test
    fun `readOffchainAttestation falls back to legacy lp json suffix`() {
        val hash = "hash1"
        val json = """{"type":"legacy"}"""
        fakeStorage.storeDirect(hash, "$hash.lp.json", json)

        val read = store.readOffchainAttestation(hash)
        assertEquals(json, read)
    }

    @Test
    fun `readOnchainAttestation reads onchain suffix only`() {
        val hash = "hash1"
        val legacyJson = """{"type":"legacy"}"""
        val onchainJson = """{"txHash":"0xabc"}"""
        fakeStorage.storeDirect(hash, "$hash.lp.json", legacyJson)
        fakeStorage.storeDirect(hash, "$hash.lp.onchain.json", onchainJson)

        assertEquals(onchainJson, store.readOnchainAttestation(hash))
    }

    @Test
    fun `readOnchainAttestation does not fall back to legacy suffix`() {
        val hash = "hash1"
        val legacyJson = """{"type":"legacy"}"""
        fakeStorage.storeDirect(hash, "$hash.lp.json", legacyJson)

        assertNull(store.readOnchainAttestation(hash))
    }
}

// -------------------------------------------------------------------------
// Fake StorageProvider (in-memory, tracks saveTextCallCount)
// -------------------------------------------------------------------------

class FakeStorageProvider : StorageProvider {

    var saveTextCallCount = 0
    var savedPath: String? = null
    var saveError: Exception? = null
    private val store = HashMap<String, ByteArray>()

    /** Key = "$hash/$identifier" */
    private fun key(hash: String, identifier: String) = "$hash/$identifier"

    override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener) {
        saveTextCallCount++

        saveError?.let {
            listener.saveFailed(it)
            return
        }

        store[key(hash, identifier)] = data.toByteArray(Charsets.UTF_8)
        listener.saveSuccessful(hash, savedPath ?: identifier)
    }

    override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener) {
        saveError?.let {
            listener.saveFailed(it)
            return
        }
        store[key(hash, identifier)] = data.toByteArray(Charsets.UTF_8)
        listener.saveSuccessful(hash, savedPath ?: identifier)
    }

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener) {
        val bytes = stream.readBytes()
        store[key(hash, identifier)] = bytes
        listener.saveSuccessful(hash, identifier)
    }

    override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener) {
        store[key(hash, identifier)] = data
        listener.saveSuccessful(hash, identifier)
    }

    override fun getInputStream(hash: String, identifier: String): InputStream? {
        val bytes = store[key(hash, identifier)] ?: return null
        return bytes.inputStream()
    }

    override fun proofExists(hash: String): Boolean =
        store.keys.any { it.startsWith("$hash/") }

    override fun proofIdentifierExists(hash: String, identifier: String): Boolean =
        store.containsKey(key(hash, identifier))

    override fun getProofSet(hash: String): ArrayList<Uri> = ArrayList()

    override fun getProofItem(uri: Uri): InputStream? = null

    fun storeDirect(hash: String, identifier: String, data: String) {
        store[key(hash, identifier)] = data.toByteArray(Charsets.UTF_8)
    }
}
