package org.witness.proofmode.lp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.ProofMode
import org.witness.proofmode.util.ProofEventReceiver
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class AutoCaptureZeroLeakageTest {

    private lateinit var context: Context
    private var enqueueCount = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        FeatureFlags.init(context)
        FeatureFlags.lpEnabled = false
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.BOTH
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
    fun lpDisabled_enqueueInterceptorNotCalledFromReceiver() {
        val intent = android.content.Intent(ProofMode.EVENT_PROOF_GENERATED).apply {
            putExtra(ProofMode.EVENT_PROOF_EXTRA_URI, "content://test/1")
            putExtra(ProofMode.EVENT_PROOF_EXTRA_HASH, "hash1")
        }
        ProofEventReceiver().onReceive(context, intent)
        assertEquals(0, enqueueCount)
    }

    @Test
    fun lpDisabled_directEnqueueDoesNotQueue() {
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests(null)
        AutoCaptureLocationAttestationOrchestrator.enqueue(
            context,
            android.net.Uri.parse("content://test/1"),
            "hash1",
        )
        assertEquals(0, AutoCaptureLocationAttestationOrchestrator.pendingCountForTests())
    }
}
