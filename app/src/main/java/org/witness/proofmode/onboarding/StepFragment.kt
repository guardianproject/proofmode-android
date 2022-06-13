package org.witness.proofmode.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.witness.proofmode.databinding.OnboardingStepBinding

class StepFragment : Fragment() {
    private lateinit var stepBinding:OnboardingStepBinding
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        stepBinding = OnboardingStepBinding.inflate(layoutInflater,container,false)
        arguments?.let {
            stepBinding.step.setText(it.getInt(ARG_STEP,0))
            stepBinding.title.setText(it.getInt(ARG_TITLE,0))
            stepBinding.content.setText(it.getInt(ARG_CONTENT,0))
            val illustration = it.getInt(ARG_ILLUSTRATION,0)
            if(illustration != 0) {
                stepBinding.illustration.setImageResource(illustration)
            }

        }
        stepBinding.btnPrevious.setOnClickListener {
            if (activity is OnboardingStepListener) {
                (activity as OnboardingStepListener?)!!.onPreviousPressed()
            }
        }
        return stepBinding.root
    }

    companion object {
        private const val ARG_STEP = "step"
        private const val ARG_TITLE = "title"
        private const val ARG_CONTENT = "content"
        private const val ARG_ILLUSTRATION = "illustration"
        private const val ARG_ILLUSTRATION_OFFSET = "illustration_offset"
        fun newInstance(
            idStep: Int,
            idTitle: Int,
            idContent: Int,
            idIllustration: Int,
            illustrationOffset: Int
        ): StepFragment {
            val f = StepFragment()
            val args = Bundle()
            args.putInt(ARG_STEP, idStep)
            args.putInt(ARG_TITLE, idTitle)
            args.putInt(ARG_CONTENT, idContent)
            args.putInt(ARG_ILLUSTRATION, idIllustration)
            args.putInt(ARG_ILLUSTRATION_OFFSET, illustrationOffset)
            f.arguments = args
            return f
        }
    }
}