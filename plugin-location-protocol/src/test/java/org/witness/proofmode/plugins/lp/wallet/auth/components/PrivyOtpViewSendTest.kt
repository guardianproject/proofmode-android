package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivyOtpViewSendTest {

    private lateinit var view: PrivyOtpView

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        view = PrivyOtpView(activity)
        activity.setContentView(view)
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun send_withValidatorError_showsItAndDoesNotSend() {
        var sent = 0
        view.onSendCode = { sent += 1; true }
        var completeError: String? = null
        view.onComplete = { completeError = it }
        view.identifierValidator = { "Enter a 10-digit phone number" }
        view.identifier = "(415) 555"
        view.findViewById<Button>(R.id.btn_send_code).performClick(); idle()
        assertEquals(0, sent)
        assertFalse(view.codeSent)
        assertEquals("Enter a 10-digit phone number", view.findViewById<TextView>(R.id.tv_step1_error).text.toString())
        assertEquals("Enter a 10-digit phone number", completeError)
    }

    @Test
    fun send_withValidatorPassing_sends() {
        var sent = 0
        view.onSendCode = { sent += 1; true }
        view.identifierValidator = { null }
        view.identifier = "(415) 555-0123"
        view.findViewById<Button>(R.id.btn_send_code).performClick(); idle()
        assertEquals(1, sent); assertTrue(view.codeSent)
    }

    @Test
    fun resend_withValidatorSet_sendsAgain() {
        var sent = 0
        view.onSendCode = { sent += 1; true }
        view.identifierValidator = { raw -> if (PhoneEntry.check(PhoneEntry.nationalDigits(raw) ?: "") is PhoneEntry.Check.Valid) null else "bad" }
        view.identifier = "(415) 555-0123"
        view.findViewById<Button>(R.id.btn_send_code).performClick(); idle()
        view.findViewById<Button>(R.id.btn_resend_code).performClick(); idle()
        assertEquals(2, sent)
        assertEquals("", view.findViewById<TextView>(R.id.tv_step2_error).text.toString())
    }

    @Test
    fun identifierInputTypeAndHint_reachTheField() {
        view.identifierInputType = InputType.TYPE_CLASS_PHONE
        view.identifierHint = PhoneEntry.MASK
        val field = view.findViewById<EditText>(R.id.et_identifier)
        assertEquals(InputType.TYPE_CLASS_PHONE, field.inputType)
        assertEquals(PhoneEntry.MASK, field.hint.toString())
    }

    @Test
    fun addIdentifierTextWatcher_formatsTyping() {
        view.addIdentifierTextWatcher(PhoneMaskTextWatcher())
        view.findViewById<EditText>(R.id.et_identifier).text.append("4155")
        assertEquals("(415) 5", view.identifier)
    }
}
