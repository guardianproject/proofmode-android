package org.witness.proofmode.plugins.lp.attestation

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.witness.proofmode.ProofMode
import org.witness.proofmode.plugins.ipfscid.CidSidecarReadiness
import org.witness.proofmode.plugins.ipfscid.CidSidecarRefs
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger

private class CountingReadiness(
    private val behavior: suspend () -> CidSidecarRefs?,
) : CidSidecarReadiness {
    val calls = AtomicInteger(0)
    var lastMediaMimeType: String? = null
    override suspend fun awaitReady(
        context: Context,
        storageProvider: StorageProvider,
        proofSetHash: String,
        mediaMimeType: String?,
    ): CidSidecarRefs? {
        calls.incrementAndGet()
        lastMediaMimeType = mediaMimeType
        return behavior()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationProtocolAttestationCoordinatorTest {

    private lateinit var storageProvider: CoordinatorFakeStorageProvider
    private lateinit var easManager: EASAttestationManager
    private lateinit var coordinator: LocationProtocolAttestationCoordinator
    private lateinit var contentResolver: ContentResolver
    private lateinit var context: Context
    private lateinit var mediaUri: Uri

    @Before
    fun setUp() {
        LocationProtocolPlugin.registerApplicationScope(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        storageProvider = CoordinatorFakeStorageProvider()
        easManager = mock()
        val defaultReadiness = CountingReadiness { null }
        coordinator = LocationProtocolAttestationCoordinator(
            storageProvider = storageProvider,
            easManager = easManager,
            cidSidecarReadiness = defaultReadiness,
        )
        contentResolver = mock()
        context = mock()
        mediaUri = mock<Uri>().also {
            whenever(it.toString()).thenReturn("content://test/media/1")
        }
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.getType(mediaUri)).thenReturn("image/jpeg")
    }

    private fun seedProof(mediaHash: String) {
        storageProvider.markProofExists(
            mediaHash,
            mapOf(
                ProofModeV1Constants.FILE_HASH_SHA_256 to mediaHash,
                ProofModeV1Constants.LOCATION_LATITUDE to "1.0",
                ProofModeV1Constants.LOCATION_LONGITUDE to "2.0",
                ProofModeV1Constants.PROOF_GENERATED to "2024-01-15T10:30:00.000Z",
                ProofModeV1Constants.FILE_PATH to "/sdcard/test.jpg",
                ProofModeV1Constants.NOTES to "",
            ),
        )
    }

    @Test
    fun `attestOffchain enriches recipePayload and mediaData from readiness refs`() = runBlocking {
        seedProof("h1")
        val readiness = CountingReadiness {
            CidSidecarRefs(rootCid = "bafyRoot", mediaCid = "bafkMedia")
        }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)
        val payloadCaptor = argumentCaptor<LocationProtocolPayload>()
        easManager.stub {
            onBlocking { createOffchainLocationAttestation(payloadCaptor.capture()) } doReturn Result.success(
                LocationProtocolAttestationResult(
                    uid = "u", schemaId = "s", attesterAddress = "a", timestamp = 1L,
                    offchainPayloadJson = "{}", artifactPath = "",
                ),
            )
        }
        assertTrue(coordinator.attestOffchain("h1", mediaUri, context).isSuccess)
        assertEquals("bafyRoot", payloadCaptor.firstValue.recipePayload[0])
        assertEquals("bafkMedia", payloadCaptor.firstValue.mediaData[0])
        assertEquals("jpg", payloadCaptor.firstValue.mediaType[0])
        assertEquals("image/jpeg", readiness.lastMediaMimeType)
        assertEquals(1, readiness.calls.get())
    }

    @Test
    fun `attestOffchain keeps baseline when readiness returns null`() = runBlocking {
        seedProof("h2")
        val readiness = CountingReadiness { null }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)
        val payloadCaptor = argumentCaptor<LocationProtocolPayload>()
        easManager.stub {
            onBlocking { createOffchainLocationAttestation(payloadCaptor.capture()) } doReturn Result.success(
                LocationProtocolAttestationResult(
                    uid = "u", schemaId = "s", attesterAddress = "a", timestamp = 1L,
                    offchainPayloadJson = "{}", artifactPath = "",
                ),
            )
        }
        assertTrue(coordinator.attestOffchain("h2", mediaUri, context).isSuccess)
        assertEquals("", payloadCaptor.firstValue.recipePayload[0])
        assertEquals("h2", payloadCaptor.firstValue.mediaData[0])
    }

    @Test
    fun `attestOffchain keeps baseline when readiness throws non-cancellation`() = runBlocking {
        seedProof("h3")
        val readiness = CountingReadiness { error("boom") }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)
        val payloadCaptor = argumentCaptor<LocationProtocolPayload>()
        easManager.stub {
            onBlocking { createOffchainLocationAttestation(payloadCaptor.capture()) } doReturn Result.success(
                LocationProtocolAttestationResult(
                    uid = "u", schemaId = "s", attesterAddress = "a", timestamp = 1L,
                    offchainPayloadJson = "{}", artifactPath = "",
                ),
            )
        }
        assertTrue(coordinator.attestOffchain("h3", mediaUri, context).isSuccess)
        assertEquals("", payloadCaptor.firstValue.recipePayload[0])
        assertEquals("h3", payloadCaptor.firstValue.mediaData[0])
    }

    @Test
    fun `attestOffchain rejects invalid coordinates before readiness or EAS`() = runBlocking {
        val mediaHash = "invalid-offchain-location"
        storageProvider.markProofExists(
            mediaHash,
            mapOf(
                ProofModeV1Constants.LOCATION_LATITUDE to "0.0",
                ProofModeV1Constants.LOCATION_LONGITUDE to "0.0",
            ),
        )
        val readiness = CountingReadiness {
            CidSidecarRefs(rootCid = "unused-root", mediaCid = "unused-media")
        }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)

        val result = coordinator.attestOffchain(mediaHash, mediaUri, context)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("cannot build LP payload"),
        )
        assertEquals(0, readiness.calls.get())
        verify(easManager, never()).createOffchainLocationAttestation(any())
        Unit
    }

    @Test
    fun `attestOffchain rethrows CancellationException`() = runBlocking {
        seedProof("h4")
        val readiness = CountingReadiness { throw CancellationException("cancel") }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)
        var threw = false
        try {
            coordinator.attestOffchain("h4", mediaUri, context)
        } catch (e: CancellationException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `BOTH sequential offchain then onchain awaits readiness once per proofSetHash`() = runBlocking {
        seedProof("hBoth")
        val readiness = CountingReadiness {
            CidSidecarRefs(rootCid = "bafyR", mediaCid = "bafkM")
        }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)
        easManager.stub {
            onBlocking { createOffchainLocationAttestation(any()) } doReturn Result.success(
                LocationProtocolAttestationResult(
                    uid = "u", schemaId = "s", attesterAddress = "a", timestamp = 1L,
                    offchainPayloadJson = "{}", artifactPath = "",
                ),
            )
            onBlocking { submitOnchainLocationAttestation(any()) } doReturn Result.success(
                OnchainSubmitResult(
                    txHash = "0x1", schemaId = "0xs", easAddress = "0xe", chainIdStr = "eip155:1",
                    rpcUrls = listOf("https://rpc"), chainDisplayName = "t", submittedAt = 1L,
                    sponsorshipActive = false, onChainAttester = "0xa",
                ),
            )
            on { buildPendingAttestationResult(any()) } doReturn LocationProtocolAttestationResult(
                uid = "", schemaId = "0xs", attesterAddress = "0xa", timestamp = 1L,
                offchainPayloadJson = """{"status":"pending_broadcast"}""", artifactPath = "",
            )
        }
        assertTrue(coordinator.attestOffchain("hBoth", mediaUri, context).isSuccess)
        assertTrue(coordinator.attestOnchain("hBoth", mediaUri, context).isSuccess)
        assertEquals(1, readiness.calls.get())
    }

    @Test
    fun `BOTH sequential memoizes null readiness — awaitReady still once`() = runBlocking {
        seedProof("hBothNull")
        val readiness = CountingReadiness { null }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)
        easManager.stub {
            onBlocking { createOffchainLocationAttestation(any()) } doReturn Result.success(
                LocationProtocolAttestationResult(
                    uid = "u", schemaId = "s", attesterAddress = "a", timestamp = 1L,
                    offchainPayloadJson = "{}", artifactPath = "",
                ),
            )
            onBlocking { submitOnchainLocationAttestation(any()) } doReturn Result.success(
                OnchainSubmitResult(
                    txHash = "0x1", schemaId = "0xs", easAddress = "0xe", chainIdStr = "eip155:1",
                    rpcUrls = listOf("https://rpc"), chainDisplayName = "t", submittedAt = 1L,
                    sponsorshipActive = false, onChainAttester = "0xa",
                ),
            )
            on { buildPendingAttestationResult(any()) } doReturn LocationProtocolAttestationResult(
                uid = "", schemaId = "0xs", attesterAddress = "0xa", timestamp = 1L,
                offchainPayloadJson = """{"status":"pending_broadcast"}""", artifactPath = "",
            )
        }
        assertTrue(coordinator.attestOffchain("hBothNull", mediaUri, context).isSuccess)
        assertTrue(coordinator.attestOnchain("hBothNull", mediaUri, context).isSuccess)
        assertEquals(1, readiness.calls.get())
    }

    @Test
    fun `attestOnchain persists pending artifact after submit`() {
        runBlocking {
            val mediaHash = "hash1"
            storageProvider.markProofExists(
                mediaHash,
                mapOf(
                    ProofModeV1Constants.FILE_HASH_SHA_256 to mediaHash,
                    ProofModeV1Constants.LOCATION_LATITUDE to "37.7749",
                    ProofModeV1Constants.LOCATION_LONGITUDE to "-122.4194",
                    ProofModeV1Constants.PROOF_GENERATED to "2024-01-15T10:30:00.000Z",
                    ProofModeV1Constants.FILE_PATH to "/sdcard/test.jpg",
                    ProofModeV1Constants.NOTES to "",
                ),
            )

            val submitResult = OnchainSubmitResult(
                txHash = "0xabc",
                schemaId = "0xschema",
                easAddress = "0xeas",
                chainIdStr = "eip155:11155111",
                rpcUrls = listOf("https://rpc.test"),
                chainDisplayName = "Sepolia",
                submittedAt = 1L,
                sponsorshipActive = true,
                onChainAttester = "0xattester",
            )
            val pendingJson =
                """{"status":"pending_broadcast","txHash":"0xabc"}"""
            easManager.stub {
                onBlocking { submitOnchainLocationAttestation(any()) } doReturn Result.success(submitResult)
                on { buildPendingAttestationResult(submitResult) } doReturn LocationProtocolAttestationResult(
                    uid = "",
                    schemaId = "0xschema",
                    attesterAddress = "0xattester",
                    timestamp = 1L,
                    offchainPayloadJson = pendingJson,
                    artifactPath = "",
                )
            }

            val result = coordinator.attestOnchain(mediaHash, mediaUri, context)

            assertTrue(result.isSuccess)
            assertEquals(
                pendingJson,
                LocationProtocolArtifactStore(storageProvider).readPendingOnchainAttestation(mediaHash),
            )
            verify(easManager).submitOnchainLocationAttestation(any())
        }
    }

    @Test
    fun `attestOnchain rejects invalid coordinates before readiness or EAS`() = runBlocking {
        val mediaHash = "invalid-onchain-location"
        storageProvider.markProofExists(
            mediaHash,
            mapOf(
                ProofModeV1Constants.LOCATION_LATITUDE to "not-a-number",
                ProofModeV1Constants.LOCATION_LONGITUDE to "2.0",
            ),
        )
        val readiness = CountingReadiness {
            CidSidecarRefs(rootCid = "unused-root", mediaCid = "unused-media")
        }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)

        val result = coordinator.attestOnchain(mediaHash, mediaUri, context)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("cannot build LP payload"),
        )
        assertEquals(0, readiness.calls.get())
        verify(easManager, never()).submitOnchainLocationAttestation(any())
        Unit
    }

    private class CoordinatorFakeStorageProvider : StorageProvider {
        private val proofHashes = mutableSetOf<String>()
        private val store = HashMap<String, ByteArray>()

        fun markProofExists(hash: String, proofData: Map<String, String> = emptyMap()) {
            proofHashes.add(hash)
            if (proofData.isNotEmpty()) {
                val identifier = hash + ProofMode.PROOF_FILE_JSON_TAG
                store["$hash/$identifier"] = JSONObject(proofData).toString().toByteArray(Charsets.UTF_8)
            }
        }

        override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener) {
            store["$hash/$identifier"] = data.toByteArray(Charsets.UTF_8)
            listener.saveSuccessful(hash, identifier)
        }

        override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener) {
            store["$hash/$identifier"] = data.toByteArray(Charsets.UTF_8)
            listener.saveSuccessful(hash, identifier)
        }

        override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener) {
            listener.saveSuccessful(hash, identifier)
        }

        override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener) {
            store["$hash/$identifier"] = data
            listener.saveSuccessful(hash, identifier)
        }

        override fun getInputStream(hash: String, identifier: String): InputStream? {
            val bytes = store["$hash/$identifier"] ?: return null
            return bytes.inputStream()
        }

        override fun proofExists(hash: String): Boolean = proofHashes.contains(hash)

        override fun proofIdentifierExists(hash: String, identifier: String): Boolean =
            store.containsKey("$hash/$identifier")

        override fun getProofSet(hash: String): ArrayList<Uri> = ArrayList()

        override fun getProofItem(uri: Uri): InputStream? = null
    }
}
