package org.witness.proofmode.plugins.ipfscid

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

class CidWorkScheduler {
    private data class CoalesceState(
        val sidecarWriteInFlight: AtomicBoolean = AtomicBoolean(false),
        val pendingSidecarRefresh: AtomicBoolean = AtomicBoolean(false),
    )

    private val coalesce = ConcurrentHashMap<String, CoalesceState>()

    fun enqueueCoalescedWork(
        proofSetHash: String,
        executor: ExecutorService,
        work: () -> Unit,
        onPendingRefresh: (proofSetHash: String) -> Unit = {},
    ) {
        val state = coalesce.computeIfAbsent(proofSetHash) { CoalesceState() }
        if (!state.sidecarWriteInFlight.compareAndSet(false, true)) {
            state.pendingSidecarRefresh.set(true)
            return
        }
        executor.submit {
            try {
                work()
            } catch (e: Exception) {
                Timber.e(e, "CID sidecar work failed for proof set %s", proofSetHash)
            } finally {
                val pending = state.pendingSidecarRefresh.getAndSet(false)
                state.sidecarWriteInFlight.set(false)
                if (pending) onPendingRefresh(proofSetHash)
            }
        }
    }

    fun markPendingRefresh(proofSetHash: String) {
        coalesce.computeIfAbsent(proofSetHash) { CoalesceState() }
            .pendingSidecarRefresh.set(true)
    }

    fun resetSchedulerForTests() = coalesce.clear()
}
