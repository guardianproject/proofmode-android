package org.witness.proofmode.plugins.lp.wallet.auth.components

import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivyOtpViewResendTest {

    @Test
    fun resend_invokesOnSendCode_again_andStaysOnStep2() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val view = PrivyOtpView(activity)
        activity.setContentView(view)

        var sendCount = 0
        view.onSendCode = {
            sendCount += 1
            true
        }
        view.identifier = "user@example.com"
        view.findViewById<Button>(R.id.btn_send_code).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(view.codeSent)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.layout_step2).visibility)
        assertEquals(1, sendCount)
        assertEquals(View.GONE, view.findViewById<View>(R.id.overlay_step1_loading).visibility)
        assertTrue(view.findViewById<Button>(R.id.btn_send_code).isEnabled)

        view.findViewById<Button>(R.id.btn_resend_code).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, sendCount)
        assertTrue(view.codeSent)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.layout_step2).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.layout_step1).visibility)
    }

    @Test
    fun resend_whenSendFails_staysOnStep2() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val view = PrivyOtpView(activity)
        activity.setContentView(view)
        var sendCount = 0
        view.onSendCode = {
            sendCount += 1
            sendCount == 1
        }
        view.identifier = "user@example.com"
        view.findViewById<Button>(R.id.btn_send_code).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, view.findViewById<View>(R.id.overlay_step1_loading).visibility)
        assertTrue(view.findViewById<Button>(R.id.btn_send_code).isEnabled)
        view.findViewById<Button>(R.id.btn_resend_code).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, sendCount)
        assertTrue(view.codeSent)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.layout_step2).visibility)
        assertEquals(
            "Unable to send code",
            view.findViewById<android.widget.TextView>(R.id.tv_step2_error).text.toString(),
        )
    }

    @Test
    fun resend_whenTooManyRequests_showsWaitToRetry() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val view = PrivyOtpView(activity)
        activity.setContentView(view)
        var sendCount = 0
        view.onSendCode = {
            sendCount += 1
            if (sendCount == 1) {
                true
            } else {
                throw org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException(
                    "Too many requests. Please wait to try again.",
                )
            }
        }
        view.identifier = "user@example.com"
        view.findViewById<Button>(R.id.btn_send_code).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        view.findViewById<Button>(R.id.btn_resend_code).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(view.codeSent)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.layout_step2).visibility)
        assertEquals(
            activity.getString(R.string.auth_otp_too_many_requests),
            view.findViewById<android.widget.TextView>(R.id.tv_step2_error).text.toString(),
        )
    }
}
