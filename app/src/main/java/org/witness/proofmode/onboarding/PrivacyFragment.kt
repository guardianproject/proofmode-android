package org.witness.proofmode.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.witness.proofmode.databinding.OnboardingPrivacyBinding

class PrivacyFragment : Fragment() {
    private lateinit var binding:OnboardingPrivacyBinding
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = OnboardingPrivacyBinding.inflate(inflater)

        binding.apply {
            btnPrevious.setOnClickListener {
                if (activity is OnboardingStepListener) {
                    (activity as OnboardingStepListener?)!!.onPreviousPressed()
                }
            }

            btnContinue.setOnClickListener {
                if (activity is OnboardingStepListener) {
                    (activity as OnboardingStepListener?)?.onNextPressed()
                }
            }

            btnSettings.setOnClickListener {
                if (activity is OnboardingStepListener) {
                    (activity as OnboardingStepListener?)?.onSettingsPressed()
                }
            }
        }

        return binding.root
    }

    companion object {
        fun newInstance(): PrivacyFragment {
            return PrivacyFragment()
        }
    }
}