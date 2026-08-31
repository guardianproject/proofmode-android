package org.witness.proofmode.plugins.lp.wallet.auth

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.auth.pages.EmailOtpPage
import org.witness.proofmode.plugins.lp.wallet.auth.pages.SelectorPage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletAuthBottomSheetOtpResetTest {

    @Before
    fun skipOnboarding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WalletOnboardingPreferences(context).persistSkipPreference(skip = true)
    }

    @Test
    fun onOtpBackRequested_resetsCodeSentOnEmailPage() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = showSheet(activity)
        val emailPage = attachEmailOtpPage(sheet)

        emailPage.setCodeSentForTests(true)
        assertTrue(emailPage.isCodeSentForTests())

        sheet.onOtpBackRequested()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sheet.childFragmentManager.executePendingTransactions()

        assertFalse(emailPage.isCodeSentForTests())
    }

    @Test
    fun handleBackNavigation_closeButton_resetsCodeSentOnEmailPage() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = showSheet(activity)
        val emailPage = attachEmailOtpPage(sheet)

        emailPage.setCodeSentForTests(true)
        assertTrue(emailPage.isCodeSentForTests())

        sheet.requireView().findViewById<ImageButton>(R.id.btn_close_auth_sheet).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sheet.childFragmentManager.executePendingTransactions()

        assertFalse(emailPage.isCodeSentForTests())
    }

    @Test
    fun skipOnboarding_showsSelectorInAuthHost() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = showSheet(activity)
        val host = sheet.requireView().findViewById<ViewGroup>(R.id.auth_page_host)
        val pager = sheet.requireView()
            .findViewById<View>(R.id.auth_view_pager)

        assertEquals(View.GONE, pager.visibility)
        assertEquals(View.VISIBLE, host.visibility)
        val selector = sheet.childFragmentManager.fragments.filterIsInstance<SelectorPage>().firstOrNull()
        assertNotNull(selector)
        assertTrue(isDescendantOf(selector!!.requireView(), host))
    }

    @Test
    fun reopeningEmailAfterBack_startsWithCodeSentFalse() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = showSheet(activity)
        val first = attachEmailOtpPage(sheet)
        first.setCodeSentForTests(true)

        sheet.onOtpBackRequested()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sheet.childFragmentManager.executePendingTransactions()

        val second = attachEmailOtpPage(sheet)
        assertFalse(second.isCodeSentForTests())
    }

    private fun showSheet(activity: FragmentActivity): WalletAuthBottomSheet {
        val sheet = WalletAuthBottomSheet.newConnectFlow()
        sheet.show(activity.supportFragmentManager, WalletAuthBottomSheet.TAG)
        activity.supportFragmentManager.executePendingTransactions()
        return sheet
    }

    private fun attachEmailOtpPage(sheet: WalletAuthBottomSheet): EmailOtpPage {
        sheet.navigateToPage(WalletAuthBottomSheet.AuthPage.EMAIL_OTP)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sheet.childFragmentManager.executePendingTransactions()

        val host = sheet.requireView().findViewById<ViewGroup>(R.id.auth_page_host)
        val emailPage = sheet.childFragmentManager.fragments.filterIsInstance<EmailOtpPage>().firstOrNull()
        assertNotNull("EmailOtpPage must attach in auth_page_host", emailPage)
        assertTrue(
            "Email OTP must be hosted in auth_page_host, not the pager",
            isDescendantOf(emailPage!!.requireView(), host),
        )
        return emailPage
    }
}

private fun isDescendantOf(child: View, ancestor: View): Boolean {
    var current: View? = child
    while (current != null) {
        if (current === ancestor) return true
        current = current.parent as? View
    }
    return false
}
