package org.witness.proofmode.plugins.lp.wallet.auth.pages

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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
import org.witness.proofmode.plugins.lp.wallet.auth.WalletAuthBottomSheet
import org.witness.proofmode.plugins.lp.wallet.auth.WalletOnboardingPreferences
import org.witness.proofmode.plugins.lp.wallet.auth.components.PhoneEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmsOtpPageTest {

    @Before
    fun skipOnboarding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WalletOnboardingPreferences(context).persistSkipPreference(skip = true)
    }

    @Test
    fun phoneField_isMaskedAndIncompleteNumberIsRejected() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = showSheet(activity)
        val page = attachSmsOtpPage(sheet)
        val root = page.requireView()
        val identifier = root.findViewById<EditText>(R.id.et_identifier)

        assertEquals(InputType.TYPE_CLASS_PHONE, identifier.inputType)
        assertEquals(PhoneEntry.MASK, identifier.hint.toString())

        identifier.text.append("415555")
        assertEquals("(415) 555", identifier.text.toString())

        root.findViewById<Button>(R.id.btn_send_code).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            activity.getString(R.string.auth_sms_phone_incomplete),
            root.findViewById<TextView>(R.id.tv_step1_error).text.toString(),
        )
        assertFalse(page.isCodeSentForTests())
    }

    private fun showSheet(activity: FragmentActivity): WalletAuthBottomSheet {
        val sheet = WalletAuthBottomSheet.newConnectFlow()
        sheet.show(activity.supportFragmentManager, WalletAuthBottomSheet.TAG)
        activity.supportFragmentManager.executePendingTransactions()
        return sheet
    }

    private fun attachSmsOtpPage(sheet: WalletAuthBottomSheet): SmsOtpPage {
        sheet.navigateToPage(WalletAuthBottomSheet.AuthPage.SMS_OTP)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sheet.childFragmentManager.executePendingTransactions()

        val host = sheet.requireView().findViewById<ViewGroup>(R.id.auth_page_host)
        val smsPage = sheet.childFragmentManager.fragments.filterIsInstance<SmsOtpPage>().firstOrNull()
        assertNotNull("SmsOtpPage must attach in auth_page_host", smsPage)
        assertTrue(
            "SMS OTP must be hosted in auth_page_host, not the pager",
            isDescendantOf(smsPage!!.requireView(), host),
        )
        return smsPage
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
