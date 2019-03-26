package org.witness.proofmode;

import android.annotation.SuppressLint;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import com.github.paolorotolo.appintro.AppIntroFragment;

import org.witness.proofmode.onboarding.DottedProgressView;
import org.witness.proofmode.onboarding.NoSwipeViewPager;
import org.witness.proofmode.onboarding.OnboardingStepListener;
import org.witness.proofmode.onboarding.PrivacyFragment;
import org.witness.proofmode.onboarding.StepFragment;
import org.witness.proofmode.onboarding.WelcomeFragment;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity implements OnboardingStepListener {

    private NoSwipeViewPager pager;
    private DottedProgressView indicator;
    private ImageButton btnNext;
    private List<Fragment> fragmentList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();
        setContentView(R.layout.activity_onboarding);

        pager = findViewById(R.id.pager);
        indicator = findViewById(R.id.indicator);
        btnNext = findViewById(R.id.btnNext);

        fragmentList = new ArrayList<>();

        pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {
            }

            @Override
            public void onPageSelected(int i) {
                indicator.setCurrentDot(i - 1);
                showHideIndicator();
            }

            @Override
            public void onPageScrollStateChanged(int i) {

            }
        });

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onNextPressed();
            }
        });

        fragmentList.add(WelcomeFragment.newInstance());
        fragmentList.add(StepFragment.newInstance(R.string.onboarding_step1, R.string.onboarding_step1_title, R.string.onboarding_step1_content, R.raw.onboard_welcome));
        fragmentList.add(StepFragment.newInstance(R.string.onboarding_step2, R.string.onboarding_step2_title, R.string.onboarding_step2_content, R.raw.onboard_welcome));
        fragmentList.add(StepFragment.newInstance(R.string.onboarding_step3, R.string.onboarding_step3_title, R.string.onboarding_step3_content, R.raw.onboard_welcome));
        fragmentList.add(StepFragment.newInstance(R.string.onboarding_step4, R.string.onboarding_step4_title, R.string.onboarding_step4_content, R.raw.onboard_welcome));
        fragmentList.add(PrivacyFragment.newInstance());

        // Set adapter
        indicator.setNumberOfDots(fragmentList.size() - 2);
        pager.setOffscreenPageLimit(fragmentList.size());
        pager.setAdapter(new OnboardingPagerAdapter(getSupportFragmentManager()));

        showHideIndicator();
    }

    private void showHideIndicator() {
        int lastItem = fragmentList.size() - 1;
        int currentItem = pager.getCurrentItem();
        indicator.setVisibility((currentItem == 0 || currentItem == lastItem) ? View.GONE : View.VISIBLE);
        btnNext.setVisibility((currentItem == 0 || currentItem == lastItem) ? View.GONE : View.VISIBLE);
        if (currentItem == 0) {
            pager.setAllowedSwipeDirection(NoSwipeViewPager.SwipeDirection.none);
        } else if (currentItem == 1) {
            pager.setAllowedSwipeDirection(NoSwipeViewPager.SwipeDirection.right);
        } else {
            pager.setAllowedSwipeDirection(NoSwipeViewPager.SwipeDirection.all);
        }
    }

    @Override
    public void onNextPressed() {
        if (pager.getCurrentItem() < (fragmentList.size() - 1)) {
            pager.setCurrentItem(pager.getCurrentItem() + 1, true);
        }
    }

    @Override
    public void onPreviousPressed() {
        if (pager.getCurrentItem() > 0) {
            pager.setCurrentItem(pager.getCurrentItem() - 1, true);
        }
    }

    private class OnboardingPagerAdapter extends FragmentPagerAdapter {

        public OnboardingPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int i) {
            return fragmentList.get(i);
        }

        @Override
        public int getCount() {
            return fragmentList.size();
        }
    }
}
