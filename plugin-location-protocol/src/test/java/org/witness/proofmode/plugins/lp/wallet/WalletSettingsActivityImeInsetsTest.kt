package org.witness.proofmode.plugins.lp.wallet

import android.content.Context
import android.view.View
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.TestWalletStackReset
import org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationTimedOutException

@RunWith(RobolectricTestRunner::class)
// SDK 34, not the house 28 pin: below API 30 the platform WindowInsets has no ime() type, so the
// synthesized keyboard inset is dropped when it crosses ViewCompat's listener wrapper.
@Config(sdk = [34])
class WalletSettingsActivityImeInsetsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
        TestWalletStackReset.reset()
    }

    private fun launch(): WalletSettingsActivity = launchWalletSettingsWith(
        context,
        FakeWalletConnector(WalletUnavailable(WalletCreationTimedOutException())),
    )

    @Test
    fun imeInset_padsScrollContainerBottom() {
        val activity = launch()
        val scroll = activity.findViewById<ScrollView>(R.id.scroll_wallet_settings)
        assertNotNull(
            "Project ID field is not inside the ScrollView bound by id",
            scroll.findViewById<View>(R.id.et_zerodev_project_id),
        )

        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 700))
            .build()
        scroll.dispatchApplyWindowInsets(insets.toWindowInsets()!!)

        assertEquals(700, scroll.paddingBottom)
    }
}
