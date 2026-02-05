package org.witness.proofmode.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager.OnPageChangeListener
import org.witness.proofmode.R
import org.witness.proofmode.SettingsActivity
import org.witness.proofmode.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity(), OnboardingStepListener {
    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var pager: NoSwipeViewPager
    private lateinit var indicator: DottedProgressView
    private lateinit var btnNext: ImageButton
    private var fragmentList: MutableList<Fragment> = ArrayList()
    private var onlyTutorial = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)

        supportActionBar?.hide()
        setContentView(binding.root)
        pager = binding.pager
        indicator = binding.indicator
        btnNext = binding.btnNext
        fragmentList = ArrayList()
        pager.addOnPageChangeListener(object : OnPageChangeListener {
            override fun onPageScrolled(i: Int, v: Float, i1: Int) {}
            override fun onPageSelected(i: Int) {
                if (onlyTutorial) {
                    indicator.currentDot = i
                } else {
                    indicator.currentDot = i - 1
                }
                showHideIndicator()
            }

            override fun onPageScrollStateChanged(i: Int) {}
        })
        btnNext.setOnClickListener { onNextPressed() }
        onlyTutorial = intent.getBooleanExtra(ARG_ONLY_TUTORIAL, false)
        if (!onlyTutorial) {
            fragmentList.add(WelcomeFragment.newInstance())
        }
        fragmentList.add(
            StepFragment.newInstance(
                R.string.onboarding_step1,
                R.string.onboarding_step1_title,
                R.string.onboarding_step1_content,
                R.drawable.ic_ill_tut01,
                0
            )
        )
        fragmentList.add(
            StepFragment.newInstance(
                R.string.onboarding_step2,
                R.string.onboarding_step2_title,
                R.string.onboarding_step2_content,
                R.drawable.ic_ill_tut02,
                20
            )
        )
        fragmentList.add(
            StepFragment.newInstance(
                R.string.onboarding_step3,
                R.string.onboarding_step3_title,
                R.string.onboarding_step3_content,
                R.drawable.ic_ill_tut03,
                40
            )
        )
        fragmentList.add(
            StepFragment.newInstance(
                R.string.onboarding_step4,
                R.string.onboarding_step4_title,
                R.string.onboarding_step4_content,
                R.drawable.ic_ill_tut04,
                60
            )
        )
        if (!onlyTutorial) {
            fragmentList.add(PrivacyFragment.newInstance())
        }

        // Set adapter
        if (onlyTutorial) {
            indicator.numberOfDots = fragmentList.size
        } else {
            indicator.numberOfDots = fragmentList.size - 2
        }
        indicator.currentDot = 0
        pager.offscreenPageLimit = fragmentList.size
        pager.adapter = OnboardingPagerAdapter(supportFragmentManager)
        showHideIndicator()
    }

    private fun showHideIndicator() {
        if (onlyTutorial) {
            indicator.visibility = View.VISIBLE
            btnNext.visibility = View.VISIBLE
            pager.setAllowedSwipeDirection(NoSwipeViewPager.SwipeDirection.ALL)
            return
        }
        val lastItem = fragmentList.size - 1
        val currentItem = pager.currentItem
        indicator.visibility =
            if (currentItem == 0 || currentItem == lastItem) View.GONE else View.VISIBLE
        btnNext.visibility =
            if (currentItem == 0 || currentItem == lastItem) View.GONE else View.VISIBLE
        when (currentItem) {
            0 -> {
                pager.setAllowedSwipeDirection(NoSwipeViewPager.SwipeDirection.NONE)
            }

            1 -> {
                pager.setAllowedSwipeDirection(NoSwipeViewPager.SwipeDirection.RIGHT)
            }

            else -> {
                pager.setAllowedSwipeDirection(NoSwipeViewPager.SwipeDirection.ALL)
            }
        }
    }

    override fun onNextPressed() {
        if (pager.currentItem < fragmentList.size - 1) {
            pager.setCurrentItem(pager.currentItem + 1, true)
        } else {
            // Done
            finish()
        }
    }

    override fun onPreviousPressed() {
        if (pager.currentItem > 0) {
            pager.setCurrentItem(pager.currentItem - 1, true)
        } else if (onlyTutorial) {
            // Close
            finish()
        }
    }

    override fun onBackPressed() {
        if (pager.currentItem > 0) {
            onPreviousPressed()
            return
        }
        super.onBackPressed()
    }

    override fun onSettingsPressed() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private inner class OnboardingPagerAdapter(fm: FragmentManager?) : FragmentPagerAdapter(
        fm!!
    ) {
        override fun getItem(i: Int): Fragment {
            return fragmentList[i]
        }

        override fun getCount(): Int {
            return fragmentList.size
        }
    }

    companion object {
        // Set this arg to "true" to only show the tutorial steps
        const val ARG_ONLY_TUTORIAL = "only_tutorial"
    }
}