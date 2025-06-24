package org.witness.proofmode.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.witness.proofmode.org.witness.proofmode.ui.DataLegendActivity
import org.witness.proofmode.databinding.OnboardingWelcomeBinding

class WelcomeFragment : Fragment() {
    private var _binding: OnboardingWelcomeBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = OnboardingWelcomeBinding.inflate(inflater, container, false)
        binding.btnLearnMore.setOnClickListener {
            val intent = Intent(activity, DataLegendActivity::class.java)
            requireActivity().startActivity(intent)
        }
        binding.btnNext.setOnClickListener {
            if (activity is OnboardingStepListener) {
                (activity as OnboardingStepListener).onNextPressed()
            }
        }
        return binding.root
    }

    companion object {
        fun newInstance(): WelcomeFragment {
            val f = WelcomeFragment()
            val args = Bundle()
            f.arguments = args
            return f
        }
    }
}