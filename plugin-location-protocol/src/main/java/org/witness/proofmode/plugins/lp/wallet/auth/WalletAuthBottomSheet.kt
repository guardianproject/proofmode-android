package org.witness.proofmode.plugins.lp.wallet.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.witness.proofmode.plugins.lp.R
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin
import org.witness.proofmode.plugins.lp.wallet.auth.pages.EmailOtpPage
import org.witness.proofmode.plugins.lp.wallet.auth.pages.OnboardingSlideFragment
import org.witness.proofmode.plugins.lp.wallet.auth.pages.SelectorPage
import org.witness.proofmode.plugins.lp.wallet.auth.pages.SmsOtpPage
import org.witness.proofmode.plugins.wallet.infra.api.WalletAuthClient

class WalletAuthBottomSheet() : BottomSheetDialogFragment() {
    private var viewPager: ViewPager2? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var currentPage: AuthPage = AuthPage.SELECTOR
    private var sessionOnboardingCount: Int = 0
    private var onboardingTourOnly: Boolean = false
    private lateinit var onboardingPrefs: WalletOnboardingPreferences

    private fun persistSkipIfNeeded(skipCheckbox: CheckBox) {
        if (skipCheckbox.isChecked) {
            onboardingPrefs.persistSkipPreference(skip = true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_wallet_auth_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        onboardingPrefs = WalletOnboardingPreferences(requireContext())
        onboardingTourOnly = arguments?.getBoolean(ARG_ONBOARDING_TOUR_ONLY) == true
        sessionOnboardingCount = onboardingPrefs.initialSessionSlideCount(onboardingTourOnly)

        viewPager = view.findViewById(R.id.auth_view_pager)
        val closeButton = view.findViewById<ImageButton>(R.id.btn_close_auth_sheet)

        val nextButton = view.findViewById<Button>(R.id.btn_onboarding_next)
        val skipCheckbox = view.findViewById<CheckBox>(R.id.cb_skip_onboarding)
        if (sessionOnboardingCount > 0) {
            nextButton.visibility = View.VISIBLE
        }

        nextButton.setOnClickListener {
            val current = viewPager?.currentItem ?: 0
            if (onboardingTourOnly && current == sessionOnboardingCount - 1) {
                persistSkipIfNeeded(skipCheckbox)
                dismissAllowingStateLoss()
                return@setOnClickListener
            }
            val target = WalletOnboardingNavigation.nextOnboardingPage(current, sessionOnboardingCount)
            if (target != null) {
                if (target == sessionOnboardingCount) {
                    persistSkipIfNeeded(skipCheckbox)
                }
                viewPager?.setCurrentItem(target, true)
            }
        }

        if (sessionOnboardingCount > 0) {
            skipCheckbox.visibility = View.VISIBLE
        }

        skipCheckbox.setOnCheckedChangeListener { _, _ ->
            // No persistence until onboarding completes or sheet dismisses.
        }

        val authPageCount = if (onboardingTourOnly) 0 else AuthPage.entries.size
        viewPager?.adapter = AuthPagerAdapter(this, sessionOnboardingCount, authPageCount)
        viewPager?.isUserInputEnabled = false
        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val authIndex = position - sessionOnboardingCount
                if (authIndex >= 0) {
                    viewPager?.isUserInputEnabled = false
                    skipCheckbox.visibility = View.GONE
                    nextButton.visibility = View.GONE
                    currentPage = AuthPage.fromIndex(authIndex)
                } else {
                    viewPager?.isUserInputEnabled = true
                    skipCheckbox.visibility = View.VISIBLE
                    nextButton.visibility = View.VISIBLE
                    if (position == sessionOnboardingCount - 1) {
                        nextButton.setText(
                            if (onboardingTourOnly) {
                                R.string.onboarding_done
                            } else {
                                R.string.onboarding_get_started
                            },
                        )
                    } else {
                        nextButton.setText(R.string.onboarding_next)
                    }
                    currentPage = AuthPage.SELECTOR
                }
            }
        }
        pageChangeCallback = callback
        viewPager?.registerOnPageChangeCallback(callback)

        closeButton.setOnClickListener {
            if (!handleBackNavigation()) {
                dismissAllowingStateLoss()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!handleBackNavigation()) {
                        dismissAllowingStateLoss()
                    }
                }
            },
        )
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        view?.findViewById<CheckBox>(R.id.cb_skip_onboarding)?.let { persistSkipIfNeeded(it) }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        val pager = viewPager
        val callback = pageChangeCallback
        if (pager != null && callback != null) {
            pager.unregisterOnPageChangeCallback(callback)
        }
        pager?.adapter = null
        pageChangeCallback = null
        viewPager = null
        super.onDestroyView()
    }

    fun navigateToPage(page: AuthPage) {
        currentPage = page
        viewPager?.setCurrentItem(sessionOnboardingCount + page.ordinal, true)
    }

    fun onOtpBackRequested() {
        navigateToPage(AuthPage.SELECTOR)
    }

    fun completeAuth(error: String?) {
        if (error == null) {
            parentFragmentManager.setFragmentResult(
                RESULT_KEY,
                bundleOf(RESULT_ERROR_KEY to null),
            )
            dismissAllowingStateLoss()
            return
        }

        context?.let { ctx ->
            Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show()
        }
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            bundleOf(RESULT_ERROR_KEY to error),
        )
    }

    fun authClient(): WalletAuthClient = WalletSigningPlugin.authClient()

    private fun handleBackNavigation(): Boolean {
        val currentPosition = viewPager?.currentItem ?: return false
        if (currentPosition > 0) {
            if (currentPosition < sessionOnboardingCount) {
                viewPager?.setCurrentItem(currentPosition - 1, true)
            } else if (currentPage != AuthPage.SELECTOR) {
                navigateToPage(AuthPage.SELECTOR)
            } else {
                return false
            }
            return true
        }
        return false
    }

    private class AuthPagerAdapter(
        parent: Fragment,
        private val onboardingSlides: Int,
        private val authPageCount: Int,
    ) : FragmentStateAdapter(parent) {

        override fun getItemCount(): Int = onboardingSlides + authPageCount

        override fun createFragment(position: Int): Fragment {
            if (position < onboardingSlides) {
                return createOnboardingSlide(position)
            }
            return when (AuthPage.fromIndex(position - onboardingSlides)) {
                AuthPage.SELECTOR -> SelectorPage()
                AuthPage.EMAIL_OTP -> EmailOtpPage()
                AuthPage.SMS_OTP -> SmsOtpPage()
            }
        }

        private fun createOnboardingSlide(index: Int): Fragment {
            return when (index) {
                0 -> OnboardingSlideFragment.newInstance(
                    iconRes = R.drawable.ic_onboarding_identity,
                    titleRes = R.string.onboarding_slide_1_title,
                    bodyRes = R.string.onboarding_slide_1_body,
                )
                1 -> OnboardingSlideFragment.newInstance(
                    iconRes = R.drawable.ic_onboarding_privy,
                    titleRes = R.string.onboarding_slide_2_title,
                    bodyRes = R.string.onboarding_slide_2_body,
                )
                2 -> OnboardingSlideFragment.newInstance(
                    iconRes = R.drawable.ic_onboarding_attestation,
                    titleRes = R.string.onboarding_slide_3_title,
                    bodyRes = R.string.onboarding_slide_3_body,
                )
                else -> throw IllegalArgumentException("Invalid onboarding index: $index")
            }
        }
    }

    enum class AuthPage {
        SELECTOR,
        EMAIL_OTP,
        SMS_OTP,
        ;

        companion object {
            fun fromIndex(index: Int): AuthPage {
                return entries.getOrElse(index) { SELECTOR }
            }
        }
    }

    companion object {
        const val TAG = "WalletAuthBottomSheet"
        const val RESULT_KEY = "wallet_auth_result"
        const val RESULT_ERROR_KEY = "wallet_auth_error"
        private const val ARG_ONBOARDING_TOUR_ONLY = "onboarding_tour_only"

        fun newConnectFlow(): WalletAuthBottomSheet = WalletAuthBottomSheet().apply {
            arguments = bundleOf(ARG_ONBOARDING_TOUR_ONLY to false)
        }

        fun newOnboardingTour(): WalletAuthBottomSheet = WalletAuthBottomSheet().apply {
            arguments = bundleOf(ARG_ONBOARDING_TOUR_ONLY to true)
        }
    }
}
