package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import org.witness.proofmode.plugin.ProofArtifactSavedHook
import org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry
import org.witness.proofmode.plugin.ProofmodePlugin
import org.witness.proofmode.plugin.ProofWriteHook
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object IpfsCidPlugin : ProofmodePlugin {
    private val registered = AtomicBoolean(false)
    private val sidecarExecutor = Executors.newSingleThreadExecutor()
    private var registeredStorageProvider: StorageProvider? = null
    private var registeredContext: Context? = null

    override fun register(context: Context) {
        register(
            context = context,
            storageProvider = DefaultStorageProvider(context.applicationContext),
            gate = LocalIpfsCidGate::isEnabled,
        )
    }

    fun register(
        context: Context,
        storageProvider: StorageProvider,
        gate: (Context) -> Boolean = LocalIpfsCidGate::isEnabled,
    ) {
        if (!gate(context)) {
            registered.set(false)
            registeredStorageProvider = null
            registeredContext = null
            Timber.d("IpfsCidPlugin: gate off — no hooks attached")
            return
        }
        if (!registered.compareAndSet(false, true)) {
            Timber.d("IpfsCidPlugin: already registered — skipping hook attach")
            return
        }
        val appContext = context.applicationContext
        registeredStorageProvider = storageProvider
        registeredContext = appContext
        val writeHook = ProofWriteHook { event ->
            ProofSetCidSidecarWriter.scheduleInitialSidecarWrite(event, sidecarExecutor)
        }
        val artifactHook = ProofArtifactSavedHook { proofSetHash, artifactBasename ->
            val ctx = registeredContext ?: return@ProofArtifactSavedHook
            val provider = registeredStorageProvider ?: return@ProofArtifactSavedHook
            if (ProofSetCidMembershipPolicy.isExcludedFromManifest(artifactBasename)) return@ProofArtifactSavedHook
            if (!LocalIpfsCidGate.isEnabled(ctx)) return@ProofArtifactSavedHook
            if (!ProofSetCidMembershipPolicy.triggersSidecarRefresh(ctx, artifactBasename)) return@ProofArtifactSavedHook
            ProofSetCidSidecarWriter.scheduleCidSidecarRefresh(
                proofSetHash, provider, sidecarExecutor, ctx,
            )
        }
        ProofWriteHookRegistry.register(writeHook)
        ProofArtifactSavedHookRegistry.register(artifactHook)
        Timber.i("IpfsCidPlugin: hooks attached")
    }

    /** Test-only: reset guard between tests. */
    fun clearRegistrationStateForTests() {
        registered.set(false)
        registeredStorageProvider = null
        registeredContext = null
        ProofWriteHookRegistry.clearForTests()
        ProofArtifactSavedHookRegistry.clearForTests()
        ProofSetCidSidecarWriter.resetSidecarWriterTestState()
    }

    internal fun registeredStorageProviderForTests(): StorageProvider? = registeredStorageProvider
}
