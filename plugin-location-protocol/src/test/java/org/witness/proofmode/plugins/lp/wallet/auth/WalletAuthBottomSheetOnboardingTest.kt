package org.witness.proofmode.plugins.lp.wallet.auth

import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletAuthBottomSheetOnboardingTest {

    @Before
    fun clearPrefs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(
            WalletOnboardingPreferences.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun nextButton_advancesFromSlide1_whenSkipChecked() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = WalletAuthBottomSheet()
        sheet.show(activity.supportFragmentManager, WalletAuthBottomSheet.TAG)
        activity.supportFragmentManager.executePendingTransactions()

        val root = sheet.requireView()
        val skip = root.findViewById<CheckBox>(org.witness.proofmode.plugins.lp.R.id.cb_skip_onboarding)
        val next = root.findViewById<Button>(org.witness.proofmode.plugins.lp.R.id.btn_onboarding_next)
        val pager = root.findViewById<ViewPager2>(org.witness.proofmode.plugins.lp.R.id.auth_view_pager)

        skip.isChecked = true
        next.performClick()

        assertEquals("Next must advance to slide 2 after skip is checked", 1, pager.currentItem)
    }

    @Test
    fun tourOnly_lastSlideDone_dismissesWithoutReachingAuth() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = WalletAuthBottomSheet.newOnboardingTour()
        sheet.show(activity.supportFragmentManager, WalletAuthBottomSheet.TAG)
        activity.supportFragmentManager.executePendingTransactions()

        val pager = sheet.requireView()
            .findViewById<ViewPager2>(org.witness.proofmode.plugins.lp.R.id.auth_view_pager)
        pager.setCurrentItem(2, false)
        activity.supportFragmentManager.executePendingTransactions()
        sheet.requireView()
            .findViewById<Button>(org.witness.proofmode.plugins.lp.R.id.btn_onboarding_next)
            .performClick()
        activity.supportFragmentManager.executePendingTransactions()

        assertTrue(sheet.dialog == null || !sheet.isAdded)
    }
}
