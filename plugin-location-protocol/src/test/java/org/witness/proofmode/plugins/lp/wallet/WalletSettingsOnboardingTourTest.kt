package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.auth.WalletAuthBottomSheet
import org.witness.proofmode.plugins.lp.wallet.auth.WalletOnboardingPreferences
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSettingsOnboardingTourTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
        WalletSigningPlugin.configure(WalletSdkConfig.fromBuildConfig())
        WalletSigningPlugin.register(context)
        WalletOnboardingPreferences(context).persistSkipPreference(skip = true)
    }

    @Test
    fun showOnboardingGuide_clearsSkipPrefAndShowsTourSheet() {
        val intent = Intent(context, WalletSettingsActivity::class.java)
        val controller = Robolectric.buildActivity(WalletSettingsActivity::class.java, intent)
        controller.create().start().visible()

        controller.get().findViewById<android.widget.Button>(R.id.btn_show_onboarding_guide).performClick()
        controller.get().supportFragmentManager.executePendingTransactions()

        val sheet = controller.get().supportFragmentManager
            .findFragmentByTag(WalletAuthBottomSheet.TAG) as WalletAuthBottomSheet
        assertFalse(WalletOnboardingPreferences(context).isSkipEnabled())
        // Tour sheet is visible with onboarding controls
        sheet.requireView().findViewById<android.widget.CheckBox>(R.id.cb_skip_onboarding)
    }
}
