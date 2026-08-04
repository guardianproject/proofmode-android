package org.witness.proofmode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class FeatureFlagsAutoCaptureTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        FeatureFlags.resetForTests(context)
    }

    @Test
    fun autoCaptureLpMode_defaultsToOff() {
        assertEquals(AutoCaptureLpMode.OFF, FeatureFlags.autoCaptureLpMode)
    }

    @Test
    fun autoCaptureLpMode_persistsRoundTrip() {
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.BOTH
        assertEquals(AutoCaptureLpMode.BOTH, FeatureFlags.autoCaptureLpMode)
    }
}
