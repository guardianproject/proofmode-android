package org.witness.proofmode.plugins.lp.wallet

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.witness.proofmode.plugins.lp.TestWalletStackReset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSettingsActivityUnregisteredTest {

    @Before
    fun setUp() {
        TestWalletStackReset.reset()
    }

    @Test
    fun onCreate_whenWalletStackNotRegistered_finishesWithoutCrash() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            WalletSettingsActivity::class.java,
        )
        val controller: ActivityController<WalletSettingsActivity> =
            Robolectric.buildActivity(WalletSettingsActivity::class.java, intent)
        controller.create()
        assertTrue(controller.get().isFinishing)
    }
}
