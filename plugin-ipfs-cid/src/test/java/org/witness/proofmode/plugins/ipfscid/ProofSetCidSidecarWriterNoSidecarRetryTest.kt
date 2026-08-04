package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetCidSidecarWriterNoSidecarRetryTest {
    private lateinit var context: Context

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
    fun refreshCidSidecar_noSidecar_doesNotBusyRetry() {
        val hash = "nosidecarbusy01"
        val sidecarReads = AtomicInteger(0)
        val storage = object : StorageProvider {
            override fun getProofSet(h: String): java.util.ArrayList<Uri> =
                java.util.ArrayList(listOf(Uri.parse("file:///fake/$h.proof.csv")))

            override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: StorageListener?) {}
            override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {}
            override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
            override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
            override fun getInputStream(h: String, identifier: String): InputStream? {
                if (identifier == IpfsCidSidecar.sidecarBasename(h)) {
                    sidecarReads.incrementAndGet()
                }
                return null
            }
            override fun proofExists(hash: String) = false
            override fun proofIdentifierExists(hash: String, identifier: String) = false
            override fun getProofItem(uri: Uri?): InputStream? = null
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            ProofSetCidSidecarWriter.scheduleCidSidecarRefresh(hash, storage, executor, context)
            // Keep the executor live so a self-reschedule loop can run if present.
            Thread.sleep(400)
            val reads = sidecarReads.get()
            assertEquals(
                "no-sidecar refresh must run once, not busy-retry while executor is live (saw $reads reads)",
                1,
                reads,
            )
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
