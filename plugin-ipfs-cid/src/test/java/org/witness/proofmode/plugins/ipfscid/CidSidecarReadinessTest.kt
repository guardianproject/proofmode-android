package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.ProofMode
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CidSidecarReadinessTest {

    private lateinit var context: Context
    private val hash = "deadbeef"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun usableSnap(
        root: String = "bafyRoot",
        mediaCid: String = "bafkMedia",
        extraFiles: Map<String, String> = emptyMap(),
        extraTsizes: Map<String, Long> = emptyMap(),
    ): SidecarSnapshot {
        val mediaName = "$hash.jpg"
        return SidecarSnapshot(
            rootCid = root,
            files = mapOf(mediaName to mediaCid) + extraFiles,
            tsizes = mapOf(mediaName to 10L) + extraTsizes,
        )
    }

    private fun enableGate() {
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
    }

    private class CountingStorage(
        private val sidecarProvider: () -> ByteArray?,
    ) : StorageProvider {
        val getInputStreamCalls = AtomicInteger(0)
        override fun getInputStream(hash: String, identifier: String): InputStream? {
            getInputStreamCalls.incrementAndGet()
            val bytes = sidecarProvider() ?: return null
            return bytes.inputStream()
        }
        override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener) =
            listener.saveSuccessful(hash, identifier)
        override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener) =
            listener.saveSuccessful(hash, identifier)
        override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener) =
            listener.saveSuccessful(hash, identifier)
        override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener) =
            listener.saveSuccessful(hash, identifier)
        override fun proofExists(hash: String) = false
        override fun proofIdentifierExists(hash: String, identifier: String) = false
        override fun getProofSet(hash: String) = ArrayList<Uri>()
        override fun getProofItem(uri: Uri): InputStream? = null
    }

    private fun encodeUsable(
        root: String = "bafyRoot",
        mediaCid: String = "bafkMedia",
        filesExtra: Map<String, String> = emptyMap(),
    ): ByteArray {
        val media = "$hash.jpg"
        return IpfsCidSidecar.encode(
            rootCid = root,
            files = mapOf(media to mediaCid) + filesExtra,
            tsizes = mapOf(media to 10L),
            computedAtMs = 1L,
        )
    }

    @Test
    fun isUsable_requiresNonBlankRootAndMediaLeaf() {
        assertTrue(DefaultCidSidecarReadiness.isUsable(usableSnap(), hash))
        assertFalse(DefaultCidSidecarReadiness.isUsable(usableSnap(root = "  "), hash))
        assertFalse(
            DefaultCidSidecarReadiness.isUsable(
                SidecarSnapshot(rootCid = "bafy", files = emptyMap(), tsizes = emptyMap()),
                hash,
            ),
        )
    }

    @Test
    fun isPreferComplete_requiresEnabledNotaryBasenamesInFilesOnly() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        val incomplete = usableSnap()
        assertFalse(DefaultCidSidecarReadiness.isPreferComplete(context, incomplete, hash))
        val complete = usableSnap(extraFiles = mapOf("$hash.ots" to "bafkOts"))
        assertTrue(DefaultCidSidecarReadiness.isPreferComplete(context, complete, hash))
        // tsizes for .ots not required:
        assertTrue(complete.tsizes["$hash.ots"] == null)
    }

    @Test
    fun resolveTimeoutMs_30sWhenBothNotaryPrefsOff_else90s() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, false)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        assertEquals(30_000L, DefaultCidSidecarReadiness.resolveTimeoutMs(context))
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()
        assertEquals(90_000L, DefaultCidSidecarReadiness.resolveTimeoutMs(context))
    }

    @Test
    fun awaitReady_gateOff_returnsNullWithoutStorageIo() = runTest {
        // gate cleared in @Before → off
        val storage = CountingStorage { error("should not read") }
        val readiness = DefaultCidSidecarReadiness()
        val refs = readiness.awaitReady(context, storage, hash, "image/jpeg")
        assertNull(refs)
        assertEquals(0, storage.getInputStreamCalls.get())
    }

    @Test
    fun awaitReady_preferComplete_earlyExit() = runTest {
        enableGate()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        val storage = CountingStorage {
            encodeUsable(filesExtra = mapOf("$hash.ots" to "bafkOts"))
        }
        var now = 0L
        val readiness = DefaultCidSidecarReadiness(
            nowMs = { now },
            delayFn = { ms -> now += ms },
            timeoutMsOverride = 30_000L,
        )
        val refs = readiness.awaitReady(context, storage, hash, "image/jpeg")
        assertEquals("bafyRoot", refs!!.rootCid)
        assertEquals("bafkMedia", refs.mediaCid)
    }

    @Test
    fun awaitReady_timeout_usable_firstFallback() = runTest {
        enableGate()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        val storage = CountingStorage { encodeUsable() } // usable, missing .ots
        var now = 0L
        val readiness = DefaultCidSidecarReadiness(
            nowMs = { now },
            delayFn = { ms -> now += ms },
            timeoutMsOverride = 1_500L,
        )
        val refs = readiness.awaitReady(context, storage, hash, "image/jpeg")
        assertEquals("bafyRoot", refs!!.rootCid)
        assertEquals("bafkMedia", refs.mediaCid)
    }

    @Test
    fun awaitReady_timeout_unusable_returnsNull() = runTest {
        enableGate()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, false)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        val storage = CountingStorage { null }
        var now = 0L
        val readiness = DefaultCidSidecarReadiness(
            nowMs = { now },
            delayFn = { ms -> now += ms },
            timeoutMsOverride = 1_000L,
        )
        assertNull(readiness.awaitReady(context, storage, hash, "image/jpeg"))
    }

    @Test
    fun awaitReady_decodeError_continuesPollingThenNull() = runTest {
        enableGate()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, false)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        val storage = CountingStorage { "{not-json".toByteArray() }
        var now = 0L
        val readiness = DefaultCidSidecarReadiness(
            nowMs = { now },
            delayFn = { ms -> now += ms },
            timeoutMsOverride = 1_000L,
        )
        assertNull(readiness.awaitReady(context, storage, hash, "image/jpeg"))
        assertTrue(storage.getInputStreamCalls.get() >= 2)
    }

    @Test
    fun awaitReady_cancellation_propagates() = runTest {
        enableGate()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, false)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        val storage = CountingStorage { null }
        var now = 0L
        val readiness = DefaultCidSidecarReadiness(
            nowMs = { now },
            delayFn = { ms ->
                now += ms
                throw CancellationException("cancelled")
            },
            timeoutMsOverride = 30_000L,
        )
        var threw = false
        try {
            readiness.awaitReady(context, storage, hash, "image/jpeg")
        } catch (e: CancellationException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun awaitReady_bareMediaKey_migratedViaSidecarReader() = runTest {
        enableGate()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, false)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        val storage = CountingStorage {
            IpfsCidSidecar.encode(
                rootCid = "bafyRoot",
                files = mapOf(hash to "bafkLegacy"),
                tsizes = mapOf(hash to 99L),
                computedAtMs = 1L,
            )
        }
        val readiness = DefaultCidSidecarReadiness(
            nowMs = { 0L },
            delayFn = {},
            timeoutMsOverride = 30_000L,
        )
        // With both notary off, prefer-complete == usable on first poll
        val refs = readiness.awaitReady(context, storage, hash, "image/jpeg")
        assertEquals("bafkLegacy", refs!!.mediaCid)
    }
}
