package org.witness.proofmode.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.witness.proofmode.databinding.OnboardingStepBinding

class StepFragment : Fragment() {
    private var _binding: OnboardingStepBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = OnboardingStepBinding.inflate(inflater, container, false)
        var tv = binding.step
        tv.setText(requireArguments().getInt(ARG_STEP, 0))
        tv = binding.title
        tv.setText(requireArguments().getInt(ARG_TITLE, 0))
        tv = binding.content
        tv.setText(requireArguments().getInt(ARG_CONTENT, 0))
        val illustration = requireArguments().getInt(ARG_ILLUSTRATION, 0)
        if (illustration != 0) {
            binding.illustration.setImageResource(illustration)
        }
        binding.btnPrevious.setOnClickListener {
            if (activity is OnboardingStepListener) {
                (activity as OnboardingStepListener?)!!.onPreviousPressed()
            }
        }
        return binding.root
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