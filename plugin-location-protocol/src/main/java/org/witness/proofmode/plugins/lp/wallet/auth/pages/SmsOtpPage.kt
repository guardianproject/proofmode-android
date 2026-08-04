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

class SmsOtpPage : Fragment() {

    private var parentSheet: WalletAuthBottomSheet? = null
    private lateinit var otpView: PrivyOtpView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_sms_otp_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parentSheet = parentFragment as? WalletAuthBottomSheet
        otpView = view.findViewById(R.id.otp_view)
        otpView.identifierLabel = "Phone Number"
        otpView.codeLabel = "Verification Code"

        otpView.onSendCode = { phone ->
            val authClient = parentSheet?.authClient()
            if (authClient == null) {
                Timber.tag(TAG).e("Wallet auth client unavailable for sendSmsCode")
                false
            } else {
                val normalizedPhone = try {
                    PrivyOtpView.phoneNumberToE164(phone)
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "Phone normalization failed for sendSmsCode")
                    null
                }

                if (normalizedPhone == null) {
                    false
                } else {
                    try {
                        authClient.sendSmsCode(normalizedPhone)
                        true
                    } catch (e: WalletAuthException) {
                        Timber.tag(TAG).e(e, "Send SMS code failed")
                        false
                    } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "Unexpected error sending SMS code")
                        false
                    }
                }
            }
        }

        otpView.onVerifyCode = { code, phone ->
            val authClient = parentSheet?.authClient()
            if (authClient == null) {
                Timber.tag(TAG).e("Wallet auth client unavailable for loginWithSmsCode")
                false
            } else {
                val normalizedPhone = try {
                    PrivyOtpView.phoneNumberToE164(phone)
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "Phone normalization failed for loginWithSmsCode")
                    null
                }

                if (normalizedPhone == null) {
                    false
                } else {
                    try {
                        authClient.loginWithSmsCode(normalizedPhone, code)
                        true
                    } catch (e: WalletAuthException) {
                        Timber.tag(TAG).e(e, "Login with SMS code failed")
                        false
                    } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "Unexpected error verifying SMS code")
                        false
                    }
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
        private const val TAG = "SmsOtpPage"
    }
}
