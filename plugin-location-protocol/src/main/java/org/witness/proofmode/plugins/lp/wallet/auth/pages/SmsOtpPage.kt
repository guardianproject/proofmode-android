package org.witness.proofmode.plugins.lp.wallet.auth.pages

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.auth.WalletAuthBottomSheet
import org.witness.proofmode.plugins.lp.wallet.auth.components.PhoneEntry
import org.witness.proofmode.plugins.lp.wallet.auth.components.PhoneMaskTextWatcher
import org.witness.proofmode.plugins.lp.wallet.auth.components.PrivyOtpView
import org.witness.proofmode.plugins.lp.wallet.auth.components.VerifyOutcome
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNetworkUnavailableException
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
        otpView.identifierInputType = InputType.TYPE_CLASS_PHONE
        otpView.identifierHint = PhoneEntry.MASK
        otpView.addIdentifierTextWatcher(PhoneMaskTextWatcher())
        otpView.identifierValidator = { raw ->
            val digits = PhoneEntry.nationalDigits(raw) ?: ""
            if (PhoneEntry.check(digits) is PhoneEntry.Check.Valid) null else getString(R.string.auth_sms_phone_incomplete)
        }

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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: WalletAuthException) {
                        Timber.tag(TAG).e(e, "Send SMS code failed")
                        throw e
                    } catch (e: WalletNetworkUnavailableException) {
                        Timber.tag(TAG).e(e, "Send SMS code failed: network unavailable")
                        throw e
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
                VerifyOutcome.Failed(getString(R.string.auth_otp_verify_failed))
            } else {
                val normalizedPhone = try {
                    PrivyOtpView.phoneNumberToE164(phone)
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "Phone normalization failed for loginWithSmsCode")
                    null
                }

                if (normalizedPhone == null) {
                    VerifyOutcome.Failed(getString(R.string.auth_otp_verify_failed))
                } else {
                    try {
                        authClient.loginWithSmsCode(normalizedPhone, code)
                        VerifyOutcome.Verified
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "Login with SMS code failed")
                        VerifyOutcome.from(
                            t,
                            invalidCode = getString(R.string.auth_otp_invalid_code),
                            network = getString(R.string.auth_otp_network_unavailable),
                            tooManyRequests = getString(R.string.auth_otp_too_many_requests),
                            fallback = getString(R.string.auth_otp_verify_failed),
                        )
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

    fun resetCodeSent() {
        if (::otpView.isInitialized) {
            otpView.codeSent = false
        }
    }

    @VisibleForTesting
    internal fun setCodeSentForTests(value: Boolean) {
        if (!::otpView.isInitialized) return
        otpView.codeSent = value
    }

    @VisibleForTesting
    internal fun isCodeSentForTests(): Boolean =
        ::otpView.isInitialized && otpView.codeSent

    companion object {
        private const val TAG = "SmsOtpPage"
    }
}
