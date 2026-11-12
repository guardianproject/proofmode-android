package org.witness.proofmode.ui

import android.app.Application
import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.witness.proofmode.FeatureFlags
import org.witness.proofmode.R
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpMode
import org.witness.proofmode.plugins.lp.wallet.WalletSettingsActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class WalletRequiredDialogActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FeatureFlags.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        FeatureFlags.resetForTests(context)
        FeatureFlags.autoCaptureLpMode = AutoCaptureLpMode.BOTH
    }

    private fun launch() =
        Robolectric.buildActivity(WalletRequiredDialogActivity::class.java).create().start().resume()

    @Test
    fun walletRequiredDialog_turnOff_setsModeOffAndFinishes() {
        val controller = launch()
        val activity = controller.get()

        activity.findViewById<TextView>(R.id.wallet_required_turn_off).performClick()

        assertEquals(AutoCaptureLpMode.OFF, FeatureFlags.autoCaptureLpMode)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun walletRequiredDialog_back_setsModeOffAndFinishes() {
        val controller = launch()
        val activity = controller.get()

        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals(AutoCaptureLpMode.OFF, FeatureFlags.autoCaptureLpMode)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun walletRequiredDialog_connectWallet_startsWalletSettingsAndKeepsMode() {
        val controller = launch()
        val activity = controller.get()

        activity.findViewById<TextView>(R.id.wallet_required_connect).performClick()

        val started = shadowOf(context as Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(WalletSettingsActivity::class.java.name, started.component?.className)
        assertEquals(AutoCaptureLpMode.BOTH, FeatureFlags.autoCaptureLpMode)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun walletRequiredDialog_theme_doesNotCloseOnTouchOutside() {
        val activity = launch().get()

        val closeOnTouchOutside = TypedValue()
        assertTrue(
            activity.theme.resolveAttribute(android.R.attr.windowCloseOnTouchOutside, closeOnTouchOutside, true),
        )
        assertEquals(0, closeOnTouchOutside.data)
        assertFalse(activity.isFinishing)
        assertEquals(
            context.getString(R.string.wallet_required_dialog_title),
            activity.findViewById<TextView>(R.id.wallet_required_title).text.toString(),
        )
        assertEquals(
            context.getString(R.string.wallet_required_dialog_message),
            activity.findViewById<TextView>(R.id.wallet_required_message).text.toString(),
        )
        assertEquals(
            context.getString(R.string.wallet_required_dialog_connect),
            activity.findViewById<TextView>(R.id.wallet_required_connect).text.toString(),
        )
        assertEquals(
            context.getString(R.string.wallet_required_dialog_turn_off),
            activity.findViewById<TextView>(R.id.wallet_required_turn_off).text.toString(),
        )
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }
}
