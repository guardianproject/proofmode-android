package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivyOtpViewVerifyTest {

    private fun newView(): Pair<PrivyOtpView, FragmentActivity> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val view = PrivyOtpView(activity)
        activity.setContentView(view)
        view.identifier = "user@example.com"
        view.codeSent = true
        return view to activity
    }

    /** Typing the 6th digit triggers verification via SegmentedOtpView.onCodeComplete. */
    private fun fillCode(view: PrivyOtpView) {
        view.findViewById<EditText>(R.id.et_otp_code).setText("123456")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun signedInWithoutWallet_completesWithNullError() {
        val (view, _) = newView()
        var completed = false
        var completeError: String? = "unset"
        view.onVerifyCode = { _, _ -> VerifyOutcome.SignedInWithoutWallet }
        view.onComplete = { error ->
            completed = true
            completeError = error
        }

        fillCode(view)

        assertEquals(true, completed)
        assertNull(completeError)
        assertEquals("", view.findViewById<TextView>(R.id.tv_step2_error).text.toString())
    }

    @Test
    fun failed_showsItsMessageOnStep2() {
        val (view, _) = newView()
        var completeError: String? = null
        view.onVerifyCode = { _, _ -> VerifyOutcome.Failed("x") }
        view.onComplete = { error -> completeError = error }

        fillCode(view)

        assertEquals("x", view.findViewById<TextView>(R.id.tv_step2_error).text.toString())
        assertEquals("x", completeError)
    }

    @Test
    fun verified_completesWithNullError() {
        val (view, _) = newView()
        var completed = false
        var completeError: String? = "unset"
        view.onVerifyCode = { _, _ -> VerifyOutcome.Verified }
        view.onComplete = { error ->
            completed = true
            completeError = error
        }

        fillCode(view)

        assertEquals(true, completed)
        assertNull(completeError)
    }

    @Test
    fun thrownException_showsGenericTextNotRawMessage() {
        val (view, activity) = newView()
        var completeError: String? = null
        view.onVerifyCode = { _, _ -> throw IllegalStateException("raw internal detail") }
        view.onComplete = { error -> completeError = error }

        fillCode(view)

        val expected = activity.getString(R.string.auth_otp_verify_failed)
        assertEquals(expected, view.findViewById<TextView>(R.id.tv_step2_error).text.toString())
        assertEquals(expected, completeError)
    }
}
