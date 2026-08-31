package org.witness.proofmode.plugins.lp.wallet.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private var authHost: ViewGroup? = null
    private var nextButton: Button? = null
    private var skipCheckbox: CheckBox? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var currentPage: AuthPage = AuthPage.SELECTOR
    private var sessionOnboardingCount: Int = 0
    private var onboardingTourOnly: Boolean = false
    private var showingAuth: Boolean = false
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
        authHost = view.findViewById(R.id.auth_page_host)
        val closeButton = view.findViewById<ImageButton>(R.id.btn_close_auth_sheet)
        nextButton = view.findViewById(R.id.btn_onboarding_next)
        skipCheckbox = view.findViewById(R.id.cb_skip_onboarding)

        nextButton?.setOnClickListener {
            val current = viewPager?.currentItem ?: 0
            if (onboardingTourOnly && current == sessionOnboardingCount - 1) {
                skipCheckbox?.let { persistSkipIfNeeded(it) }
                dismissAllowingStateLoss()
                return@setOnClickListener
            }
            val target = WalletOnboardingNavigation.nextOnboardingPage(current, sessionOnboardingCount)
            if (target == sessionOnboardingCount) {
                skipCheckbox?.let { persistSkipIfNeeded(it) }
                showAuthPage(AuthPage.SELECTOR)
            } else if (target != null) {
                viewPager?.setCurrentItem(target, true)
            }
        }

        skipCheckbox?.setOnCheckedChangeListener { _, _ ->
            // No persistence until onboarding completes or sheet dismisses.
        }

        if (sessionOnboardingCount > 0) {
            nextButton?.visibility = View.VISIBLE
            skipCheckbox?.visibility = View.VISIBLE
            viewPager?.visibility = View.VISIBLE
            authHost?.visibility = View.GONE
            viewPager?.adapter = AuthPagerAdapter(this, sessionOnboardingCount)
            viewPager?.isUserInputEnabled = true
            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    skipCheckbox?.visibility = View.VISIBLE
                    nextButton?.visibility = View.VISIBLE
                    if (position == sessionOnboardingCount - 1) {
                        nextButton?.setText(
                            if (onboardingTourOnly) {
                                R.string.onboarding_done
                            } else {
                                R.string.onboarding_get_started
                            },
                        )
                    } else {
                        nextButton?.setText(R.string.onboarding_next)
                    }
                }
            }
            pageChangeCallback = callback
            viewPager?.registerOnPageChangeCallback(callback)
        } else if (!onboardingTourOnly) {
            showAuthPage(AuthPage.SELECTOR)
        }

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

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val root = view ?: return
        AuthSheetImeInsets.bind(dialog, root)
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
            isFitToContents = true
        }
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
        authHost = null
        nextButton = null
        skipCheckbox = null
        super.onDestroyView()
    }

    fun navigateToPage(page: AuthPage) {
        showAuthPage(page)
    }

    fun onOtpBackRequested() {
        resetOtpCodeSentOnVisiblePages()
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

    private fun showAuthPage(page: AuthPage) {
        showingAuth = true
        currentPage = page
        viewPager?.visibility = View.GONE
        viewPager?.isUserInputEnabled = false
        nextButton?.visibility = View.GONE
        skipCheckbox?.visibility = View.GONE
        val host = authHost ?: return
        host.visibility = View.VISIBLE
        val fragment = when (page) {
            AuthPage.SELECTOR -> SelectorPage()
            AuthPage.EMAIL_OTP -> EmailOtpPage()
            AuthPage.SMS_OTP -> SmsOtpPage()
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.auth_page_host, fragment, AUTH_PAGE_TAG)
            .commitNow()
    }

    private fun handleBackNavigation(): Boolean {
        if (showingAuth) {
            if (currentPage != AuthPage.SELECTOR) {
                resetOtpCodeSentOnVisiblePages()
                showAuthPage(AuthPage.SELECTOR)
                return true
            }
            return false
        }
        val currentPosition = viewPager?.currentItem ?: return false
        if (currentPosition > 0) {
            viewPager?.setCurrentItem(currentPosition - 1, true)
            return true
        }
        return false
    }

    private fun resetOtpCodeSentOnVisiblePages() {
        childFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is EmailOtpPage -> fragment.resetCodeSent()
                is SmsOtpPage -> fragment.resetCodeSent()
            }
        }
    }

    private class AuthPagerAdapter(
        parent: Fragment,
        private val onboardingSlides: Int,
    ) : FragmentStateAdapter(parent) {

        override fun getItemCount(): Int = onboardingSlides

        override fun createFragment(position: Int): Fragment = createOnboardingSlide(position)

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
    }

    companion object {
        const val TAG = "WalletAuthBottomSheet"
        const val RESULT_KEY = "wallet_auth_result"
        const val RESULT_ERROR_KEY = "wallet_auth_error"
        private const val ARG_ONBOARDING_TOUR_ONLY = "onboarding_tour_only"
        private const val AUTH_PAGE_TAG = "wallet_auth_page"

        fun newConnectFlow(): WalletAuthBottomSheet = WalletAuthBottomSheet().apply {
            arguments = bundleOf(ARG_ONBOARDING_TOUR_ONLY to false)
        }

        fun newOnboardingTour(): WalletAuthBottomSheet = WalletAuthBottomSheet().apply {
            arguments = bundleOf(ARG_ONBOARDING_TOUR_ONLY to true)
        }
    }
}
