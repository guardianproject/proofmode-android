package org.witness.proofmode.plugins.lp.attestation

import android.content.ContentResolver
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.witness.proofmode.ProofMode
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.util.ProofModeUtil
import java.io.InputStream
import java.lang.reflect.Field
import java.util.ArrayList
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone

/**
 * Unit tests for [LocationProtocolHelper].
 * Uses a mockable proof-map path so the payload contract can be asserted
 * deterministically without Android's org.json runtime implementation.
 */
class LocationProtocolHelperTest {

    private lateinit var storageProvider: FakeStorageProvider
    private lateinit var contentResolver: ContentResolver
    private lateinit var mediaUri: Uri
    private lateinit var proofModeUtilCompanionMock: ProofModeUtil.Companion
    private var originalProofModeUtilCompanion: Any? = null

    @Before
    fun setUp() {
        storageProvider = FakeStorageProvider()
        contentResolver = mock()
        mediaUri = mock<Uri>().also {
            whenever(it.toString()).thenReturn("content://test/media/1")
        }
        whenever(contentResolver.getType(mediaUri)).thenReturn("image/jpeg")

        proofModeUtilCompanionMock = Mockito.mock(ProofModeUtil.Companion::class.java)
        originalProofModeUtilCompanion = swapProofModeUtilCompanion(proofModeUtilCompanionMock)
    }

    @After
    fun tearDown() {
        if (::proofModeUtilCompanionMock.isInitialized && originalProofModeUtilCompanion != null) {
            swapProofModeUtilCompanion(originalProofModeUtilCompanion!!)
        }
    }

    @Test
    fun `buildPayload returns null when proof does not exist`() {
        val result = LocationProtocolHelper.buildPayload(
            mediaHash = "abc123",
            mediaUri = mediaUri,
            contentResolver = contentResolver,
            storageProvider = storageProvider
        )

        assertNull(result)
    }

    @Test
    fun `buildPayload returns null when proof map lookup fails`() {
        val hash = "abc123"
        storageProvider.markProofExists(hash)
        whenever(proofModeUtilCompanionMock.getProofHashMap(storageProvider, hash))
            .thenThrow(RuntimeException("proof map unavailable"))

        val result = LocationProtocolHelper.buildPayload(
            mediaHash = hash,
            mediaUri = mediaUri,
            contentResolver = contentResolver,
            storageProvider = storageProvider
        )

        assertNull(result)
    }

    @Test
    fun `LocationProtocolPayload_empty returns correct defaults`() {
        val payload = LocationProtocolPayload.empty()
        assertEquals(0L, payload.eventTimestamp)
        assertEquals("WGS84", payload.srs)
        assertEquals("geojson", payload.locationType)
        assertEquals("", payload.location)
        assertTrue(payload.recipeType.isEmpty())
        assertTrue(payload.recipePayload.isEmpty())
        assertTrue(payload.mediaType.isEmpty())
        assertTrue(payload.mediaData.isEmpty())
        assertEquals("", payload.memo)
    }

    @Test
    fun `buildPayload with known GPS proof data produces correct GeoJSON`() {
        val hash = "cafebabe"
        storageProvider.markProofExists(hash)
        val proofData = hashMapOf(
            ProofModeV1Constants.FILE_HASH_SHA_256 to hash,
            ProofModeV1Constants.LOCATION_LATITUDE to "37.7749",
            ProofModeV1Constants.LOCATION_LONGITUDE to "-122.4194",
            ProofModeV1Constants.PROOF_GENERATED to "2024-01-15T10:30:00.000Z",
            ProofModeV1Constants.FILE_PATH to "/sdcard/test.jpg",
            ProofModeV1Constants.NOTES to "test memo"
        )
        whenever(proofModeUtilCompanionMock.getProofHashMap(storageProvider, hash)).thenReturn(proofData)

        val result = LocationProtocolHelper.buildPayload(
            mediaHash = hash,
            mediaUri = mediaUri,
            contentResolver = contentResolver,
            storageProvider = storageProvider
        )

        assertNotNull(result)
        val payload = result!!

        val expectedTimestamp = SimpleDateFormat(
            ProofModeV1Constants.ISO_DATE_TIME_FORMAT,
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("2024-01-15T10:30:00.000Z")!!.time

        assertEquals(expectedTimestamp, payload.eventTimestamp)
        assertEquals("WGS84", payload.srs)
        assertEquals("geojson", payload.locationType)
        assertEquals("""{"type":"Point","coordinates":[-122.4194,37.7749]}""", payload.location)
        assertEquals("ProofMode", payload.recipeType[0])
        assertEquals("", payload.recipePayload[0])
        assertEquals("image/jpeg", payload.mediaType[0])
        assertEquals(hash, payload.mediaData[0])
        assertEquals("test memo", payload.memo)
    }

    @Test
    fun `buildPayload with empty GPS proof fields falls back to zero coordinates`() {
        val hash = "zero-gps"
        storageProvider.markProofExists(hash)
        val proofData = hashMapOf(
            ProofModeV1Constants.FILE_HASH_SHA_256 to hash,
            ProofModeV1Constants.LOCATION_LATITUDE to "",
            ProofModeV1Constants.LOCATION_LONGITUDE to "",
            ProofModeV1Constants.PROOF_GENERATED to "2024-01-15T10:30:00.000Z",
            ProofModeV1Constants.FILE_PATH to "/sdcard/test.jpg",
            ProofModeV1Constants.NOTES to "zero gps memo"
        )
        whenever(proofModeUtilCompanionMock.getProofHashMap(storageProvider, hash)).thenReturn(proofData)

        val result = LocationProtocolHelper.buildPayload(
            mediaHash = hash,
            mediaUri = mediaUri,
            contentResolver = contentResolver,
            storageProvider = storageProvider
        )

        assertNotNull(result)
        assertEquals("""{"type":"Point","coordinates":[0.0,0.0]}""", result!!.location)
    }

    private class FakeStorageProvider : StorageProvider {

        private val proofHashes = mutableSetOf<String>()

        fun markProofExists(hash: String) {
            proofHashes.add(hash)
        }

        override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener) {
            listener.saveSuccessful(hash, identifier)
        }

        override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener) {
            listener.saveSuccessful(hash, identifier)
        }

        override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener) {
            listener.saveSuccessful(hash, identifier)
        }

        override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener) {
            listener.saveSuccessful(hash, identifier)
        }

        override fun getInputStream(hash: String, identifier: String): InputStream? = null

        override fun proofExists(hash: String): Boolean = proofHashes.contains(hash)

        override fun proofIdentifierExists(hash: String, identifier: String): Boolean = proofHashes.contains(hash)

        override fun getProofSet(hash: String): ArrayList<Uri> = ArrayList()

        override fun getProofItem(uri: Uri): InputStream? = null
    }

    private fun swapProofModeUtilCompanion(newValue: Any): Any? {
        val field = ProofModeUtil::class.java.getDeclaredField("Companion")
        field.isAccessible = true
        val original = field.get(null)
        setStaticFinalField(field, newValue)
        return original
    }

    private fun setStaticFinalField(field: Field, value: Any) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
        val staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field) as Long
        unsafeClass.getMethod("putObjectVolatile", Any::class.java, java.lang.Long.TYPE, Any::class.java)
            .invoke(unsafe, staticFieldBase, staticFieldOffset, value)
    }
}
