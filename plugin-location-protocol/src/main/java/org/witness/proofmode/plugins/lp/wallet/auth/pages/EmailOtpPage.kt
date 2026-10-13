package org.witness.proofmode.plugins.lp.wallet.auth.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.auth.WalletAuthBottomSheet
import org.witness.proofmode.plugins.lp.wallet.auth.components.PrivyOtpView
import org.witness.proofmode.plugins.lp.wallet.auth.components.VerifyOutcome
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNetworkUnavailableException
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: WalletAuthException) {
                    Timber.tag(TAG).e(e, "Send email code failed")
                    throw e
                } catch (e: WalletNetworkUnavailableException) {
                    Timber.tag(TAG).e(e, "Send email code failed: network unavailable")
                    throw e
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
                VerifyOutcome.Failed(getString(R.string.auth_otp_verify_failed))
            } else {
                try {
                    authClient.loginWithEmailCode(email, code)
                    VerifyOutcome.Verified
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "Login with email code failed")
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
        private const val TAG = "EmailOtpPage"
    }
}
