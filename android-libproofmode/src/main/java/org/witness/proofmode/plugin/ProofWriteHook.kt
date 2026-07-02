package org.witness.proofmode.plugin

import android.content.Context
import android.net.Uri
import org.witness.proofmode.storage.StorageProvider
import java.util.concurrent.ExecutorService

data class ProofWriteEvent(
    val context: Context,
    val mediaHash: String,
    val mediaUri: Uri,
    val storageProvider: StorageProvider,
    val executor: ExecutorService,
)

/** Handlers attach from optional plugins (e.g. IpfsCidPlugin). Empty by default — NF2 zero-leakage. */
fun interface ProofWriteHook {
    fun onProofWritten(event: ProofWriteEvent)
}

/** Handlers attach from optional plugins (e.g. IpfsCidPlugin). Empty by default — NF2 zero-leakage. */
object ProofWriteHookRegistry {
    private val hooks = java.util.concurrent.CopyOnWriteArrayList<ProofWriteHook>()

    fun register(hook: ProofWriteHook) {
        hooks.addIfAbsent(hook)
    }

    fun unregister(hook: ProofWriteHook) {
        hooks.remove(hook)
    }

    fun notify(event: ProofWriteEvent) {
        hooks.forEach { it.onProofWritten(event) }
    }

    fun clearForTests() {
        hooks.clear()
    }

    fun registeredCountForTests(): Int = hooks.size
}
