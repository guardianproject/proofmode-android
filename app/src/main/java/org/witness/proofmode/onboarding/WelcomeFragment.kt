package org.witness.proofmode.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.witness.proofmode.DataLegendActivity
import org.witness.proofmode.R
import org.witness.proofmode.databinding.OnboardingWelcomeBinding

class WelcomeFragment : Fragment() {
    private var mRootView: View? = null
    private lateinit var welcomeBinding:OnboardingWelcomeBinding
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        welcomeBinding = OnboardingWelcomeBinding.inflate(layoutInflater,container,false)
        mRootView = inflater.inflate(R.layout.onboarding_welcome, container, false)
        welcomeBinding.btnLearnMore.setOnClickListener {
            val intent = Intent(activity, DataLegendActivity::class.java)
            activity?.startActivity(intent)
        }
        welcomeBinding.btnNext.setOnClickListener {
            if (activity is OnboardingStepListener) {
                (activity as OnboardingStepListener?)!!.onNextPressed()
            }
        }
        return welcomeBinding.root
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