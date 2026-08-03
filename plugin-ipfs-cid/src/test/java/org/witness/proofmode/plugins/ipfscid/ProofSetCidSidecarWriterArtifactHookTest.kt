package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.ProofMode
import org.witness.proofmode.cid.CidLib
import org.witness.proofmode.cid.NamedBytes
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetCidSidecarWriterArtifactHookTest {
    private lateinit var context: Context
    private val hash = "artifacthook0001"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        resetSidecarWriterTestState()
    }

    @After
    fun tearDown() {
        resetSidecarWriterTestState()
    }

    @Test
    fun artifactHook_denylistedSidecar_doesNotSchedule() {
        val runs = AtomicInteger(0)
        val executor = trackingExecutor(runs)
        val storage = noopStorage()
        IpfsCidPlugin.register(context, storageProvider = storage, gate = { true })
        org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry.notify(
            hash, IpfsCidSidecar.sidecarBasename(hash),
        )
        executor.shutdown()
        assertEquals(0, runs.get())
    }

    @Test
    fun artifactHook_lpArtifact_doesNotSchedule() {
        val runs = AtomicInteger(0)
        val storage = noopStorage()
        IpfsCidPlugin.register(context, storageProvider = storage, gate = { true })
        org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry.notify(
            hash, "$hash.lp.offchain.json",
        )
        assertEquals(0, runs.get())
    }

    @Test
    fun artifactHook_coreProofFile_doesNotSchedule() {
        val storage = noopStorage()
        IpfsCidPlugin.register(context, storageProvider = storage, gate = { true })
        org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry.notify(
            hash, "$hash.proof.csv",
        )
        assertEquals(0, storage.writtenCount())
    }

    @Test
    fun artifactHook_otsWhenPrefEnabled_schedulesRefresh() {
        try {
            CidLib.computeProofSetCid(listOf(NamedBytes("_", byteArrayOf(0))))
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true).commit()
        val sidecar = IpfsCidSidecar.encode(
            rootCid = "bafyOLD",
            files = mapOf("$hash.bin" to "bafkMEDIA", "$hash.proof.csv" to "bafkCSV"),
            tsizes = mapOf("$hash.bin" to 10L, "$hash.proof.csv" to 5L),
            computedAtMs = 1L,
        )
        val storage = sidecarStorage(sidecar, mapOf("$hash.ots" to byteArrayOf(1, 2, 3)))
        IpfsCidPlugin.register(context, storageProvider = storage, gate = { true })
        org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry.notify(hash, "$hash.ots")
        Thread.sleep(2000)
        assertTrue(storage.writtenCount() >= 1)
    }

    private fun trackingExecutor(runs: AtomicInteger) =
        Executors.newSingleThreadExecutor { r ->
            Thread {
                runs.incrementAndGet()
                r.run()
            }
        }

    private fun noopStorage() = CountingStorageProvider()

    private fun sidecarStorage(
        sidecarBytes: ByteArray,
        extraFiles: Map<String, ByteArray>,
    ) = object : CountingStorageProvider() {
        override fun getProofSet(h: String): java.util.ArrayList<Uri> {
            val ids = mutableListOf(IpfsCidSidecar.sidecarBasename(h), "$h.proof.csv")
            ids.addAll(extraFiles.keys)
            return java.util.ArrayList(ids.map { Uri.parse("file:///fake/$it") })
        }

        override fun getInputStream(h: String, identifier: String): InputStream? = when (identifier) {
            IpfsCidSidecar.sidecarBasename(h) -> ByteArrayInputStream(sidecarBytes)
            else -> extraFiles[identifier]?.let { ByteArrayInputStream(it) }
        }
    }

    private open class CountingStorageProvider : StorageProvider {
        private val writes = AtomicInteger(0)
        fun writtenCount() = writes.get()

        override fun getProofSet(hash: String) = java.util.ArrayList<Uri>()
        override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: StorageListener?) {
            if (data != null) writes.incrementAndGet()
        }
        override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {}
        override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
        override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
        override fun getInputStream(hash: String, identifier: String): InputStream? = null
        override fun proofExists(hash: String) = false
        override fun proofIdentifierExists(hash: String, identifier: String) = false
        override fun getProofItem(uri: Uri?): InputStream? = null
    }
}
