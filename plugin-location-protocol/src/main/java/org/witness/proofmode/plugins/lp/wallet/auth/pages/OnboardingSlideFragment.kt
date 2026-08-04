package org.witness.proofmode.plugins.lp.wallet.auth.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import org.witness.proofmode.plugins.lp.R

class OnboardingSlideFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_onboarding_slide, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        view.findViewById<ImageView>(R.id.iv_slide_icon)
            .setImageResource(args.getInt(ARG_ICON_RES))
        view.findViewById<TextView>(R.id.tv_slide_title)
            .setText(args.getInt(ARG_TITLE_RES))
        view.findViewById<TextView>(R.id.tv_slide_body)
            .setText(args.getInt(ARG_BODY_RES))
    }

    companion object {
        private const val ARG_ICON_RES = "icon_res"
        private const val ARG_TITLE_RES = "title_res"
        private const val ARG_BODY_RES = "body_res"

        fun newInstance(
            @DrawableRes iconRes: Int,
            @StringRes titleRes: Int,
            @StringRes bodyRes: Int,
        ): OnboardingSlideFragment {
            return OnboardingSlideFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_ICON_RES, iconRes)
                    putInt(ARG_TITLE_RES, titleRes)
                    putInt(ARG_BODY_RES, bodyRes)
                }
            }
        }
    }
}
