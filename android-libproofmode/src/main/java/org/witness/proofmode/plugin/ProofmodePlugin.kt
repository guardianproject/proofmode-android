package org.witness.proofmode.plugin

import android.content.Context

/**
 * Minimal contract for optional ProofMode plugin modules.
 *
 * Called from `ProofModeApp.registerPlugins()`, which `ProofModeApp.onCreate()` invokes at
 * cold start. Implementation may no-op when its feature gate is off.
 */
interface ProofmodePlugin {
    fun register(context: Context)
}
