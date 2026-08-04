package org.witness.proofmode.plugins.ipfscid

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CidWorkSchedulerTest {
    private val scheduler = CidWorkScheduler()

    @After fun tearDown() = scheduler.resetSchedulerForTests()

    @Test fun enqueueCoalescedWork_runsWorkOnExecutor() {
        val ran = AtomicInteger(0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            scheduler.enqueueCoalescedWork("hash1", executor, work = { ran.incrementAndGet() })
            executor.shutdown()
            assertEquals(true, executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(1, ran.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun enqueueCoalescedWork_secondCallWhileInFlight_setsPendingRefresh() {
        val executor = Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val runCount = AtomicInteger(0)
        try {
            val work: () -> Unit = {
                started.countDown()
                finish.await(2, TimeUnit.SECONDS)
                runCount.incrementAndGet()
            }
            scheduler.enqueueCoalescedWork("hash1", executor, work)
            started.await(2, TimeUnit.SECONDS)
            scheduler.enqueueCoalescedWork("hash1", executor, work)
            finish.countDown()
            executor.shutdown()
            assertEquals(true, executor.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(1, runCount.get())
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }
}
