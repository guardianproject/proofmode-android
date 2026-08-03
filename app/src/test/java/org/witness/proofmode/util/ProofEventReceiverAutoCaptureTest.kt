package org.witness.proofmode.util

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.ProofMode
import org.witness.proofmode.lp.AutoCaptureLocationAttestationOrchestrator
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ProofEventReceiverAutoCaptureTest {

    private lateinit var context: Context
    private var enqueueCount = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        FeatureFlags.resetForTests(context)
        enqueueCount = 0
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests { _, _, _ ->
            enqueueCount++
        }
    }

    @After
    fun tearDown() {
        AutoCaptureLocationAttestationOrchestrator.resetForTests()
    }

    @Test
    fun proofGenerated_enqueuesWhenLpEnabledAndModeActive() {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN

        deliverProofEvent(ProofMode.EVENT_PROOF_GENERATED)

        assertEquals(1, enqueueCount)
    }

    @Test
    fun proofGeneratedImport_doesNotEnqueue() {
        FeatureFlags.lpEnabled = true
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN

        deliverProofEvent(ProofMode.EVENT_PROOF_GENERATED_IMPORT)

        assertEquals(0, enqueueCount)
    }

    @Test
    fun proofGenerated_skipsWhenLpDisabled() {
        FeatureFlags.lpEnabled = false
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.OFFCHAIN

        deliverProofEvent(ProofMode.EVENT_PROOF_GENERATED)

        assertEquals(0, enqueueCount)
    }

    private fun deliverProofEvent(action: String) {
        val intent = Intent(action).apply {
            putExtra(ProofMode.EVENT_PROOF_EXTRA_URI, "content://test/media/1")
            putExtra(ProofMode.EVENT_PROOF_EXTRA_HASH, "hash1")
        }
        ProofEventReceiver().onReceive(context, intent)
    }
}
