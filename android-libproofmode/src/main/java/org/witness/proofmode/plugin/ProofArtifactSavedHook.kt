package org.witness.proofmode.plugin

/** Handlers attach from optional plugins (e.g. IpfsCidPlugin). Empty by default — NF2 zero-leakage. */
fun interface ProofArtifactSavedHook {
    fun onArtifactSaved(mediaHash: String, identifier: String)
}

/** Handlers attach from optional plugins (e.g. IpfsCidPlugin). Empty by default — NF2 zero-leakage. */
object ProofArtifactSavedHookRegistry {
    private val hooks = java.util.concurrent.CopyOnWriteArrayList<ProofArtifactSavedHook>()

    fun register(hook: ProofArtifactSavedHook) {
        hooks.addIfAbsent(hook)
    }

    fun unregister(hook: ProofArtifactSavedHook) {
        hooks.remove(hook)
    }

    fun notify(mediaHash: String, identifier: String) {
        hooks.forEach { it.onArtifactSaved(mediaHash, identifier) }
    }

    fun clearForTests() {
        hooks.clear()
    }

    fun registeredCountForTests(): Int = hooks.size
}
