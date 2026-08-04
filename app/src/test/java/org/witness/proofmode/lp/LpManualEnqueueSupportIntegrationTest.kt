package org.witness.proofmode.lp

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    @Test
    fun resolveMediaHashForAttest_usesCanonicalCacheKey() {
        val uri = Uri.parse("content://test/item/1")
        val cache = hashMapOf<String, String?>(canonicalMediaUriKey(uri) to "hash1")
        val hash = resolveMediaHashForAttest(
            storage = OrchestratorTestStorage().apply { seedProof("hash1") },
            contentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver,
            uri = uri,
            hashCache = cache,
            proofExistsResolver = { _, _, _ -> null },
        )
        assertEquals("hash1", hash)
    }

    @Test
    fun enqueueManualAttestForShareProof_enqueuesAllUrisNotAlreadyAttested() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        storage.seedProof("hash2")
        val uris = listOf(
            Uri.parse("content://test/1"),
            Uri.parse("content://test/2"),
        )
        val cache = hashMapOf<String, String?>(
            canonicalMediaUriKey(uris[0]) to "hash1",
            canonicalMediaUriKey(uris[1]) to "hash2",
        )

        val result = enqueueManualAttestForShareProof(
            appContext = context,
            uris = uris,
            leg = LpManualLeg.OFFCHAIN,
            hashCache = cache,
            storage = storage,
            proofExistsResolver = { _, _, _ -> null },
        )

        assertEquals(2, result.enqueuedCount)
        assertEquals(0, result.hashMissCount)
    }

    @Test
    fun enqueueManualAttestForShareProof_skipsUriWithExistingArtifact() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        storage.seedArtifactIdentifier("hash1", "hash1${LocationProtocolArtifactStore.OFFCHAIN_SUFFIX}")
        val uris = listOf(Uri.parse("content://test/1"))
        val cache = hashMapOf<String, String?>(canonicalMediaUriKey(uris[0]) to "hash1")

        val result = enqueueManualAttestForShareProof(
            appContext = context,
            uris = uris,
            leg = LpManualLeg.OFFCHAIN,
            hashCache = cache,
            storage = storage,
            proofExistsResolver = { _, _, _ -> null },
        )

        assertEquals(0, result.enqueuedCount)
        assertEquals(0, AutoCaptureLocationAttestationOrchestrator.pendingCountForTests())
    }

    @Test
    fun enqueueLoop_completesOnApplicationScopeAfterActivityScopeCancelled() = runTest {
        LocationProtocolPlugin.registerApplicationScope(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val activityScopeJob = Job()
        val activityScope = CoroutineScope(activityScopeJob + Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OrchestratorTestStorage()
        storage.seedProof("hash1")
        storage.seedProof("hash2")
        val uris = listOf(Uri.parse("content://test/1"), Uri.parse("content://test/2"))
        val cache = hashMapOf<String, String?>(
            canonicalMediaUriKey(uris[0]) to "hash1",
            canonicalMediaUriKey(uris[1]) to "hash2",
        )

        activityScope.launch { delay(Long.MAX_VALUE) }

        var result: ManualAttestEnqueueResult? = null
        val appScopeWork = LocationProtocolPlugin.requireApplicationScope().launch {
            result = enqueueManualAttestForShareProof(
                appContext = context,
                uris = uris,
                leg = LpManualLeg.OFFCHAIN,
                hashCache = cache,
                storage = storage,
                proofExistsResolver = { _, _, _ -> null },
            )
        }

        activityScopeJob.cancel()
        appScopeWork.join()

        assertEquals(2, result!!.enqueuedCount)
    }
}
