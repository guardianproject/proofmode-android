package org.witness.proofmode.plugins.lp.attestation

import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationProtocolArtifactStoreTest {

    private lateinit var fakeStorage: FakeStorageProvider
    private lateinit var store: LocationProtocolArtifactStore
    private lateinit var fileStorage: TempDirStorageProvider

    @Before
    fun setUp() {
        fakeStorage = FakeStorageProvider()
        store = LocationProtocolArtifactStore(fakeStorage)
        fileStorage = TempDirStorageProvider.inNewTempDir()
    }

    @After
    fun tearDown() {
        fileStorage.deleteRoot()
    }

    @Test
    fun `save is idempotent — second call does not write again, per save function`() {
        val rows: List<Pair<suspend (String, String) -> Unit, String>> = listOf(
            store::saveOffchainAttestation to LocationProtocolArtifactStore.OFFCHAIN_SUFFIX,
            store::saveOnchainAttestation to LocationProtocolArtifactStore.ONCHAIN_SUFFIX,
            store::savePendingOnchainAttestation to LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX,
        )
        for ((save, suffix) in rows) {
            fakeStorage.saveTextCallCount = 0
            val hash = "hash$suffix"

            kotlinx.coroutines.runBlocking {
                save(hash, "{}")
                save(hash, "{}")
            }

            assertEquals(suffix, 1, fakeStorage.saveTextCallCount)
            assertTrue(suffix, fakeStorage.proofIdentifierExists(hash, "$hash$suffix"))
        }
    }

    @Test
    fun `saveOffchainAttestation failure propagates the storage exception`() {
        fakeStorage.saveError = IOException("disk full")

        val thrown = try {
            kotlinx.coroutines.runBlocking {
                store.saveOffchainAttestation("hash2", "{}")
            }
            null
        } catch (e: IOException) {
            e
        }

        assertEquals("disk full", thrown?.message)
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
    fun `savePendingOnchainAttestation persists pending suffix artifact`() {
        val hash = "hash1"
        val json = """{"status":"pending_broadcast","txHash":"0xabc"}"""

        kotlinx.coroutines.runBlocking {
            store.savePendingOnchainAttestation(hash, json)
        }

        assertEquals(json, store.readPendingOnchainAttestation(hash))
        assertNull(store.readOnchainAttestation(hash))
    }

    @Test
    fun saveOnchainAttestation_deletesPendingRecord_evenWhenConfirmedAlreadySaved() = runTest {
        val fileStore = LocationProtocolArtifactStore(fileStorage)
        fileStorage.seed(HASH, PENDING_ID, "{}")

        fileStore.saveOnchainAttestation(HASH, """{"status":"confirmed"}""")

        assertEquals("""{"status":"confirmed"}""", fileStorage.read(HASH, CONFIRMED_ID))
        assertFalse(fileStorage.exists(HASH, PENDING_ID))

        // A second confirmed save (re-check racing the live confirmation) still clears a pending record.
        fileStorage.seed(HASH, PENDING_ID, "{}")
        fileStore.saveOnchainAttestation(HASH, """{"status":"confirmed"}""")
        assertFalse(fileStorage.exists(HASH, PENDING_ID))
    }

    private companion object {
        const val HASH = "hash1"
        const val PENDING_ID = HASH + LocationProtocolArtifactStore.ONCHAIN_PENDING_SUFFIX
        const val CONFIRMED_ID = HASH + LocationProtocolArtifactStore.ONCHAIN_SUFFIX
    }

    @Test
    fun `readOnchainAttestation reads onchain suffix only`() {
        val hash = "hash1"
        val onchainJson = """{"txHash":"0xabc"}"""
        fakeStorage.storeDirect(hash, "$hash.lp.onchain.json", onchainJson)

        assertEquals(onchainJson, store.readOnchainAttestation(hash))
    }
}

// -------------------------------------------------------------------------
// Fake StorageProvider (in-memory, tracks saveTextCallCount)
// -------------------------------------------------------------------------

class FakeStorageProvider : StorageProvider {

    var saveTextCallCount = 0
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
        listener.saveSuccessful(hash, identifier)
    }

    override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener) {
        saveError?.let {
            listener.saveFailed(it)
            return
        }
        store[key(hash, identifier)] = data.toByteArray(Charsets.UTF_8)
        listener.saveSuccessful(hash, identifier)
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
