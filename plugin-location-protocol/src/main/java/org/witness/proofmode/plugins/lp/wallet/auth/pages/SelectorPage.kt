package org.witness.proofmode.plugins.lp.wallet.auth.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.auth.WalletAuthBottomSheet

class SelectorPage : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_selector_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val emailButton = view.findViewById<Button>(R.id.btn_login_email)
        val smsButton = view.findViewById<Button>(R.id.btn_login_sms)

        emailButton.setOnClickListener {
            (parentFragment as? WalletAuthBottomSheet)?.navigateToPage(
                WalletAuthBottomSheet.AuthPage.EMAIL_OTP,
            )
        }

        smsButton.setOnClickListener {
            (parentFragment as? WalletAuthBottomSheet)?.navigateToPage(
                WalletAuthBottomSheet.AuthPage.SMS_OTP,
            )
        }
    }
}
