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
        binding = OnboardingPrivacyBinding.inflate(layoutInflater,container,false)
       binding.btnPrevious.setOnClickListener {
            if (activity is OnboardingStepListener) {
                (activity as OnboardingStepListener?)!!.onPreviousPressed()
            }
        }
        binding.btnContinue.setOnClickListener {
            if (activity is OnboardingStepListener) {
                (activity as OnboardingStepListener?)!!.onNextPressed()
            }
        }
        binding.btnSettings.setOnClickListener {
            if (activity is OnboardingStepListener) {
                (activity as OnboardingStepListener?)!!.onSettingsPressed()
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