package org.witness.proofmode.plugins.lp.deeplink

import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import org.witness.proofmode.plugins.lp.wallet.FakeAndroidKeyStoreProvider
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.lp.wallet.WalletSettingsActivity
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletDeepLinkRouterActivityTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val shadowApp get() = Shadows.shadowOf(context.applicationContext as Application)

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
    }

    @Test
    fun onCreate_whenWalletStackNotRegistered_doesNotStartWalletSettings() {
        assertFalse(LocationProtocolPlugin.isWalletStackRegistered())

        val uri = Uri.parse("proofmode://wallet?chain=eip155:42161")
        val controller = Robolectric.buildActivity(
            WalletDeepLinkRouterActivity::class.java,
            Intent(Intent.ACTION_VIEW, uri),
        )

        controller.create().visible()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertNull(shadowApp.nextStartedActivity)
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun onCreate_whenWalletStackNotRegistered_showsDisabledDialogMessage() {
        assertFalse(LocationProtocolPlugin.isWalletStackRegistered())

        val uri = Uri.parse("proofmode://wallet?chain=eip155:42161")
        val controller = Robolectric.buildActivity(
            WalletDeepLinkRouterActivity::class.java,
            Intent(Intent.ACTION_VIEW, uri),
        )

        controller.create().visible()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        if (dialog != null) {
            assertEquals(
                context.getString(org.witness.proofmode.plugins.lp.R.string.wallet_deep_link_feature_disabled),
                Shadows.shadowOf(dialog).message.toString(),
            )
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertTrue(controller.get().isFinishing)
        }
    }

    @Test
    fun onCreate_whenWalletStackRegistered_startsWalletSettingsWithExtras() {
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)

        val uri = Uri.parse("proofmode://wallet?chain=eip155:42161&sponsor=true")
        val controller = Robolectric.buildActivity(
            WalletDeepLinkRouterActivity::class.java,
            Intent(Intent.ACTION_VIEW, uri),
        )

        controller.create()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowApp.nextStartedActivity
        assertNotNull(started)
        assertEquals(WalletSettingsActivity::class.java.name, started.component?.className)
        assertEquals(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            started.flags and (Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        assertNotNull(started.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE))
    }
}
