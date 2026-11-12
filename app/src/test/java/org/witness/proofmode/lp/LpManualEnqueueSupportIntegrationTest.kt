package org.witness.proofmode.lp

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class LpManualEnqueueSupportIntegrationTest {

    @Before
    fun setUp() {
        LocationProtocolPlugin.registerApplicationScope(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        FeatureFlags.init(context)
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFF
        AutoCaptureLocationAttestationOrchestrator.resetForTests()
    }

    @After
    fun tearDown() {
        AutoCaptureLocationAttestationOrchestrator.resetForTests()
    }

    private class TapDones {
        val calls = mutableListOf<Pair<Int, Int>>()
        val onTapDone: (Int, Int) -> Unit = { succeeded, total -> calls += succeeded to total }
    }

    private fun completeEveryEnqueuedJobWith(
        ok: (hash: String) -> Boolean,
        intercepted: MutableList<String> = mutableListOf(),
    ): MutableList<String> {
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests { _, _, hash, callback ->
            intercepted += hash
            callback!!.invoke(ok(hash))
        }
        return intercepted
    }

    @Test
    fun loop_allAlreadyDone_callsOnTapDoneWithoutEnqueue() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OrchestratorTestStorage()
        storage.seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}")
        storage.seedArtifactIdentifier("hash2", "hash2${LocationProtocolArtifactStore.ONCHAIN_SUFFIX}")
        val items = listOf(Uri.parse("content://test/1") to "hash1", Uri.parse("content://test/2") to "hash2")
        val intercepted = completeEveryEnqueuedJobWith(ok = { true })
        val tap = TapDones()

        enqueueManualAttestForShareProof(
            appContext = context,
            items = items,
            leg = LpManualLeg.ONCHAIN,
            storage = storage,
            onTapDone = tap.onTapDone,
        )

        assertEquals(emptyList<String>(), intercepted)
        assertEquals(listOf(2 to 2), tap.calls)
    }

    @Test
    fun loop_tapDoneWaitsForEveryEnqueuedJobCallback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OrchestratorTestStorage()
        val items = listOf(Uri.parse("content://test/1") to "hash1", Uri.parse("content://test/2") to "hash2")
        val callbacks = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()
        val bothEnqueued = CompletableDeferred<Unit>()
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests { _, _, _, callback ->
            callbacks += callback!!
            if (callbacks.size == items.size) bothEnqueued.complete(Unit)
        }
        val tap = TapDones()

        val loop = LocationProtocolPlugin.requireApplicationScope().launch {
            enqueueManualAttestForShareProof(
                appContext = context,
                items = items,
                leg = LpManualLeg.OFFCHAIN,
                storage = storage,
                onTapDone = tap.onTapDone,
            )
        }
        // The loop runs on Dispatchers.IO in real time, so the wait is real, not virtual.
        withContext(Dispatchers.Default) { withTimeout(5_000) { bothEnqueued.await() } }
        callbacks[0](true)
        assertEquals(emptyList<Pair<Int, Int>>(), tap.calls)
        callbacks[1](true)
        loop.join()

        assertEquals(listOf(2 to 2), tap.calls)
    }

    @Test
    fun loop_reportsExactlyOncePerUri_acrossDuplicateExistingAndNew() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OrchestratorTestStorage()
        storage.seedArtifactIdentifier("hash2", "hash2${LocationProtocolArtifactStore.OFFCHAIN_SUFFIX}")
        val items = listOf(
            Uri.parse("content://test/1") to "hash1",
            Uri.parse("content://test/1-copy") to "hash1",
            Uri.parse("content://test/2") to "hash2",
            Uri.parse("content://test/3") to "hash3",
        )
        val intercepted = completeEveryEnqueuedJobWith(ok = { it != "hash3" })
        val tap = TapDones()

        enqueueManualAttestForShareProof(
            appContext = context,
            items = items,
            leg = LpManualLeg.OFFCHAIN,
            storage = storage,
            onTapDone = tap.onTapDone,
        )

        assertEquals(listOf("hash1", "hash1", "hash3"), intercepted)
        // hash1 twice ok, hash2 already done ok, hash3 failed
        assertEquals(listOf(3 to 4), tap.calls)
    }
}
