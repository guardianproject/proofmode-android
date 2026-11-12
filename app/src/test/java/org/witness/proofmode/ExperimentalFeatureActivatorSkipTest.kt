package org.witness.proofmode

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.witness.proofmode.lp.AutoCaptureSkipReason
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.ui.WalletRequiredDialogActivity
import org.witness.proofmode.util.ForegroundWalletActivityBinder
import org.witness.proofmode.util.TestWalletStackHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ExperimentalFeatureActivatorSkipTest {

    private lateinit var context: Context
    private lateinit var app: ProofModeApp

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = LocationSettingsActivityTestSupport.reflectiveProofModeApp(context)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        ForegroundWalletActivityBinder.resetRegistrationStateForTests()
        TestWalletStackHelper.resetWalletStack()
        LocationSettingsActivityTestSupport.tearDown()
    }

    // The skip handler's foreground read is the wallet stack's bound-activity signal, which only the
    // registered binder feeds, so the tests wire the real pair.
    private fun registerWalletStackAndBinder() {
        ForegroundWalletActivityBinder.resetRegistrationStateForTests()
        TestWalletStackHelper.resetWalletStack()
        TestWalletStackHelper.setupFakeKeyStore()
        LocationProtocolPlugin.registerWalletStack(context)
        ForegroundWalletActivityBinder.register(context as Application)
    }

    private fun toastText(): String? {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowToast.getTextOfLatestToast()
    }

    private fun startedActivityClass(): String? =
        shadowOf(context as Application).nextStartedActivity?.component?.className

    @Test
    fun skipHandler_autoWalletReason_appForeground_launchesDialogNotToast() {
        registerWalletStackAndBinder()
        Robolectric.buildActivity(Activity::class.java).setup()

        ExperimentalFeatureActivator.handleSkip(app, AutoCaptureSkipReason.AUTO_CAPTURE_WALLET_UNAVAILABLE)

        assertEquals(WalletRequiredDialogActivity::class.java.name, startedActivityClass())
        assertNull(toastText())
    }

    @Test
    fun skipHandler_autoWalletReason_appBackground_neitherDialogNorToast() {
        registerWalletStackAndBinder()
        Robolectric.buildActivity(Activity::class.java).setup().pause().stop()

        ExperimentalFeatureActivator.handleSkip(app, AutoCaptureSkipReason.AUTO_CAPTURE_WALLET_UNAVAILABLE)

        assertNull(startedActivityClass())
        assertNull(toastText())
    }

    @Test
    fun skipHandler_otherReasons_stillToast() {
        val expected = mapOf(
            AutoCaptureSkipReason.LOCATION_UNAVAILABLE to R.string.lp_auto_capture_skip_location_unavailable,
            AutoCaptureSkipReason.NO_FOREGROUND_ACTIVITY to R.string.lp_auto_capture_skip_no_activity,
        )
        for ((reason, messageRes) in expected) {
            ShadowToast.reset()

            ExperimentalFeatureActivator.handleSkip(app, reason)

            assertEquals(reason.name, context.getString(messageRes), toastText())
            assertNull(reason.name, startedActivityClass())
        }
    }
}
