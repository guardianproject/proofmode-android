package org.witness.proofmode.plugins.lp.attestation

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.witness.proofmode.ProofMode
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.ArrayList

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
        coordinator = LocationProtocolAttestationCoordinator(storageProvider, easManager)
        contentResolver = mock()
        context = mock()
        mediaUri = mock<Uri>().also {
            whenever(it.toString()).thenReturn("content://test/media/1")
        }
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.getType(mediaUri)).thenReturn("image/jpeg")
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
