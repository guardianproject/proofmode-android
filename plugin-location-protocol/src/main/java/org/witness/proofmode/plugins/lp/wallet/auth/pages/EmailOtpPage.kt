package org.witness.proofmode.plugins.lp.wallet.auth.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.auth.WalletAuthBottomSheet
import org.witness.proofmode.plugins.lp.wallet.auth.components.PrivyOtpView
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import timber.log.Timber

class EmailOtpPage : Fragment() {

    private var parentSheet: WalletAuthBottomSheet? = null
    private lateinit var otpView: PrivyOtpView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_email_otp_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parentSheet = parentFragment as? WalletAuthBottomSheet
        otpView = view.findViewById(R.id.otp_view)
        otpView.identifierLabel = "Email Address"
        otpView.codeLabel = "Verification Code"

        otpView.onSendCode = { email ->
            val authClient = parentSheet?.authClient()
            if (authClient == null) {
                Timber.tag(TAG).e("Wallet auth client unavailable for sendEmailCode")
                false
            } else {
                try {
                    authClient.sendEmailCode(email)
                    true
                } catch (e: WalletAuthException) {
                    Timber.tag(TAG).e(e, "Send email code failed")
                    false
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "Unexpected error sending email code")
                    false
                }
            }
        }

        otpView.onVerifyCode = { code, email ->
            val authClient = parentSheet?.authClient()
            if (authClient == null) {
                Timber.tag(TAG).e("Wallet auth client unavailable for loginWithEmailCode")
                false
            } else {
                try {
                    authClient.loginWithEmailCode(email, code)
                    true
                } catch (e: WalletAuthException) {
                    Timber.tag(TAG).e(e, "Login with email code failed")
                    false
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "Unexpected error verifying email code")
                    false
                }
            }
        }

        otpView.onComplete = { error ->
            parentSheet?.completeAuth(error)
        }

        view.findViewById<Button>(R.id.btn_back_to_selector).setOnClickListener {
            parentSheet?.onOtpBackRequested()
        }
    }

    override fun onDestroyView() {
        if (::otpView.isInitialized) {
            otpView.onSendCode = null
            otpView.onVerifyCode = null
            otpView.onComplete = null
        }
        parentSheet = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "EmailOtpPage"
    }
}
