package org.witness.proofmode.lp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class AutoCaptureZeroLeakageTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        FeatureFlags.init(context)
        FeatureFlags.lpEnabled = false
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.BOTH
    }

    @After
    fun tearDown() {
        AutoCaptureLocationAttestationOrchestrator.resetForTests()
    }

    @Test
    fun lpDisabled_directEnqueueDoesNotQueue() {
        val probe = CoroutineScope(SupervisorJob())
        LocationProtocolPlugin.registerApplicationScope(probe)
        AutoCaptureLocationAttestationOrchestrator.setEnqueueInterceptorForTests(null)
        AutoCaptureLocationAttestationOrchestrator.enqueue(
            context,
            android.net.Uri.parse("content://test/1"),
            "hash1",
        )
        // A started processor would be a child job of the application scope.
        assertTrue(probe.coroutineContext[Job]!!.children.toList().isEmpty())
    }
}
