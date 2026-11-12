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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.any
import org.mockito.kotlin.KArgumentCaptor
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
import org.witness.proofmode.storage.StorageProvider
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

    private lateinit var storageProvider: TempDirStorageProvider
    private lateinit var easManager: EASAttestationManager
    private lateinit var coordinator: LocationProtocolAttestationCoordinator
    private lateinit var contentResolver: ContentResolver
    private lateinit var context: Context
    private lateinit var mediaUri: Uri

    @Before
    fun setUp() {
        LocationProtocolPlugin.registerApplicationScope(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        storageProvider = TempDirStorageProvider.inNewTempDir()
        easManager = mock()
        // attestOnchain launches the background confirmation; an unstubbed outcome would be null.
        easManager.stub {
            onBlocking { confirmOnchainLocationAttestation(any()) } doReturn OnchainConfirmationOutcome.Failed(Exception("receipt poll timed out"))
        }
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

    @After
    fun tearDown() {
        storageProvider.deleteRoot()
    }

    private fun seedProof(mediaHash: String, proofData: Map<String, String>) {
        storageProvider.seed(
            mediaHash,
            mediaHash + ProofMode.PROOF_FILE_JSON_TAG,
            JSONObject(proofData).toString(),
        )
    }

    private fun seedProof(mediaHash: String) {
        seedProof(
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

    /** Stubs the offchain EAS call and returns the captor of the payload it receives. */
    private fun stubOffchainResult(): KArgumentCaptor<LocationProtocolPayload> {
        val payloadCaptor = argumentCaptor<LocationProtocolPayload>()
        easManager.stub {
            onBlocking { createOffchainLocationAttestation(payloadCaptor.capture()) } doReturn Result.success(
                LocationProtocolAttestationResult(
                    uid = "u", attesterAddress = "a", timestamp = 1L,
                    offchainPayloadJson = "{}",
                ),
            )
        }
        return payloadCaptor
    }

    private fun stubOnchainSubmit(
        submitResult: OnchainSubmitResult = OnchainSubmitResult(
            txHash = "0x1", easAddress = "0xe", chainIdStr = "eip155:1",
            rpcUrls = listOf("https://rpc"), submittedAt = 1L,
            sponsorshipActive = false, onChainAttester = "0xa",
        ),
        pendingJson: String = """{"status":"pending_broadcast"}""",
    ) {
        easManager.stub {
            onBlocking { submitOnchainLocationAttestation(any()) } doReturn Result.success(submitResult)
            on { buildPendingAttestationResult(submitResult) } doReturn LocationProtocolAttestationResult(
                uid = "", attesterAddress = "0xa", timestamp = 1L,
                offchainPayloadJson = pendingJson,
            )
        }
    }

    @Test
    fun `attestOffchain enriches recipePayload and mediaData from readiness refs`() = runBlocking {
        seedProof("h1")
        val readiness = CountingReadiness {
            CidSidecarRefs(rootCid = "bafyRoot", mediaCid = "bafkMedia")
        }
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)
        val payloadCaptor = stubOffchainResult()
        assertTrue(coordinator.attestOffchain("h1", mediaUri, context).isSuccess)
        assertEquals("bafyRoot", payloadCaptor.firstValue.recipePayload[0])
        assertEquals("bafkMedia", payloadCaptor.firstValue.mediaData[0])
        assertEquals("jpg", payloadCaptor.firstValue.mediaType[0])
        assertEquals("image/jpeg", readiness.lastMediaMimeType)
        assertEquals(1, readiness.calls.get())
    }

    @Test
    fun `attestOffchain keeps baseline payload when readiness returns null or throws`() = runBlocking {
        val rows = listOf<Pair<String, suspend () -> CidSidecarRefs?>>(
            "null" to { null },
            "throws" to { error("boom") },
        )
        for ((label, behavior) in rows) {
            val hash = "hBaseline-$label"
            seedProof(hash)
            coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, CountingReadiness(behavior))
            val payloadCaptor = stubOffchainResult()

            assertTrue(label, coordinator.attestOffchain(hash, mediaUri, context).isSuccess)

            assertEquals(label, "", payloadCaptor.firstValue.recipePayload[0])
            assertEquals(label, hash, payloadCaptor.firstValue.mediaData[0])
        }
    }

    @Test
    fun `attestOffchain rejects invalid coordinates before readiness or EAS`() = runBlocking {
        val mediaHash = "invalid-offchain-location"
        seedProof(
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
    fun `BOTH sequential offchain then onchain awaits readiness once per proofSetHash, refs or null`() = runBlocking {
        val rows = listOf<Pair<String, CidSidecarRefs?>>(
            "refs" to CidSidecarRefs(rootCid = "bafyR", mediaCid = "bafkM"),
            "null" to null,
        )
        for ((label, refs) in rows) {
            val hash = "hBoth-$label"
            seedProof(hash)
            val readiness = CountingReadiness { refs }
            coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager, readiness)
            stubOffchainResult()
            stubOnchainSubmit()

            assertTrue(label, coordinator.attestOffchain(hash, mediaUri, context).isSuccess)
            assertTrue(label, coordinator.attestOnchain(hash, mediaUri, context).isSuccess)

            assertEquals(label, 1, readiness.calls.get())
        }
    }

    @Test
    fun `attestOnchain persists pending artifact after submit`() {
        runBlocking {
            val mediaHash = "hash1"
            seedProof(
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
                easAddress = "0xeas",
                chainIdStr = "eip155:11155111",
                rpcUrls = listOf("https://rpc.test"),
                submittedAt = 1L,
                sponsorshipActive = true,
                onChainAttester = "0xattester",
            )
            val pendingJson =
                """{"status":"pending_broadcast","txHash":"0xabc"}"""
            stubOnchainSubmit(submitResult, pendingJson)

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
        seedProof(
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
}
