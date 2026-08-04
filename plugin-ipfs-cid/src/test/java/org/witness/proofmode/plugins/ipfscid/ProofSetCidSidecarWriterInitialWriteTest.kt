package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.witness.proofmode.cid.CidLib
import org.witness.proofmode.cid.NamedBytes
import org.witness.proofmode.storage.DefaultStorageProvider
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetCidSidecarWriterInitialWriteTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        resetSidecarWriterTestState()
        try {
            CidLib.computeProofSetCid(listOf(NamedBytes("_", byteArrayOf(0))))
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
    }

    @After
    fun tearDown() {
        resetSidecarWriterTestState()
    }

    @Test
    fun scheduleInitialSidecarWrite_gateOn_writesSidecar() {
        val hash = "initialwrite01"
        val dir = setupProofSetDir(context, hash)
        val mediaFile = File(dir, "media.bin").apply { writeBytes("media-payload".toByteArray()) }
        val mediaUri = Uri.fromFile(mediaFile)
        shadowOf(context.contentResolver).registerInputStream(mediaUri, FileInputStream(mediaFile))
        val provider = DefaultStorageProvider(context)
        val executor = Executors.newSingleThreadExecutor()
        val event = proofWriteEvent(context, hash, mediaFile, provider, executor, mediaUri)
        ProofSetCidSidecarWriter.scheduleInitialSidecarWrite(event, executor)
        executor.shutdown()
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        assertTrue(File(dir, IpfsCidSidecar.sidecarBasename(hash)).exists())
    }

    @Test
    fun scheduleInitialSidecarWrite_pendingRefreshTriggersSecondRefresh() {
        val hash = "coalescefollow1"
        val runCount = AtomicInteger(0)
        val started = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val scheduler = CidWorkScheduler()
        val executor = Executors.newSingleThreadExecutor()
        try {
            scheduler.enqueueCoalescedWork(
                proofSetHash = hash,
                executor = executor,
                work = {
                    runCount.incrementAndGet()
                    started.countDown()
                    finish.await(2, TimeUnit.SECONDS)
                },
                onPendingRefresh = { pendingHash ->
                    scheduler.enqueueCoalescedWork(
                        proofSetHash = pendingHash,
                        executor = executor,
                        work = { runCount.incrementAndGet() },
                    )
                },
            )
            assertTrue(started.await(2, TimeUnit.SECONDS))
            scheduler.enqueueCoalescedWork(
                proofSetHash = hash,
                executor = executor,
                work = { },
            )
            finish.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(2, runCount.get())
        } finally {
            finish.countDown()
            scheduler.resetSchedulerForTests()
            executor.shutdownNow()
        }
    }
}
