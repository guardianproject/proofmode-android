package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SegmentedOtpViewTest {
    @Test
    fun getCodeReturnsConcatenatedDigits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val otpView = SegmentedOtpView(context)
        
        // Simulate entering code manually or by pasting
        // (Since we have fields inside, let's look at setting digitFields or pasting)
        // Paste on first field triggers distributePastedCode
        val firstField = otpView.findViewById<OtpDigitEditText>(org.witness.proofmode.plugins.lp.R.id.otp_digit_0)
        assertNotNull(firstField)
        
        firstField.onPaste?.invoke("123456")
        assertEquals("123456", otpView.getCode())
    }

    @Test
    fun clearEmptiesAllFields() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val otpView = SegmentedOtpView(context)
        val firstField = otpView.findViewById<OtpDigitEditText>(org.witness.proofmode.plugins.lp.R.id.otp_digit_0)
        firstField.onPaste?.invoke("123456")
        assertEquals("123456", otpView.getCode())

        otpView.clear()
        assertEquals("", otpView.getCode())
    }

    @Test
    fun onCodeCompleteFiresWhen6DigitsEntered() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val otpView = SegmentedOtpView(context)
        var completedCode: String? = null
        otpView.onCodeComplete = { completedCode = it }

        val firstField = otpView.findViewById<OtpDigitEditText>(org.witness.proofmode.plugins.lp.R.id.otp_digit_0)
        firstField.onPaste?.invoke("654321")
        assertEquals("654321", completedCode)
    }

    @Test
    fun onCodeCompleteDoesNotFireWithFewerThan6Digits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val otpView = SegmentedOtpView(context)
        var completedCode: String? = null
        otpView.onCodeComplete = { completedCode = it }

        // Set only some fields
        val firstField = otpView.findViewById<OtpDigitEditText>(org.witness.proofmode.plugins.lp.R.id.otp_digit_0)
        firstField.setText("1")
        assertNull(completedCode)
    }
}
