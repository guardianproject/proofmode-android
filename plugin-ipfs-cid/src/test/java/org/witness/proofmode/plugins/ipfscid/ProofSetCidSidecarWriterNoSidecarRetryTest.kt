package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.ProofMode
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetCidSidecarWriterNoSidecarRetryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true).commit()
        resetSidecarWriterTestState()
    }

    @After
    fun tearDown() {
        resetSidecarWriterTestState()
    }

    @Test
    fun refreshCidSidecar_noSidecar_setsPendingRefreshAndRetries() {
        val hash = "nosidecarretry01"
        val sidecarAvailable = AtomicBoolean(false)
        val sidecarBytes = IpfsCidSidecar.encode(
            rootCid = "bafyFIRST",
            files = mapOf("$hash.bin" to "bafkMEDIA", "$hash.proof.csv" to "bafkCSV"),
            tsizes = mapOf("$hash.bin" to 10L, "$hash.proof.csv" to 5L),
            computedAtMs = 1L,
        )
        val otsBytes = byteArrayOf(0x01, 0x02, 0x03)
        val storage = object : StorageProvider {
            override fun getProofSet(h: String): java.util.ArrayList<Uri> =
                java.util.ArrayList(
                    listOf(
                        Uri.parse("file:///fake/$h.proof.csv"),
                        Uri.parse("file:///fake/$h.ots"),
                    ),
                )

            override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: StorageListener?) {
                if (identifier == IpfsCidSidecar.sidecarBasename(hash) && data != null) {
                    sidecarAvailable.set(true)
                }
            }

            override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {}
            override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
            override fun getInputStream(h: String, identifier: String): InputStream? = when {
                identifier == IpfsCidSidecar.sidecarBasename(h) && sidecarAvailable.get() ->
                    ByteArrayInputStream(sidecarBytes)
                identifier == "$h.ots" -> ByteArrayInputStream(otsBytes)
                else -> null
            }
            override fun proofExists(hash: String) = false
            override fun proofIdentifierExists(hash: String, identifier: String) = false
            override fun getProofItem(uri: Uri?): InputStream? = null
        }

        val executor = Executors.newSingleThreadExecutor()
        IpfsCidPlugin.register(context, storageProvider = storage, gate = { true })
        org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry.notify(hash, "$hash.ots")

        Thread.sleep(100)
        sidecarAvailable.set(true)

        executor.shutdown()
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))

        org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry.notify(hash, "$hash.ots")
        Thread.sleep(500)
    }
}
