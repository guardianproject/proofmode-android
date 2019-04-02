package org.witness.proofmode.onboarding;

import android.annotation.SuppressLint;
import android.content.Intent;
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

import org.witness.proofmode.MainActivity;
import org.witness.proofmode.R;
import org.witness.proofmode.SettingsActivity;
import org.witness.proofmode.onboarding.DottedProgressView;
import org.witness.proofmode.onboarding.NoSwipeViewPager;
import org.witness.proofmode.onboarding.OnboardingStepListener;
import org.witness.proofmode.onboarding.PrivacyFragment;
import org.witness.proofmode.onboarding.StepFragment;
import org.witness.proofmode.onboarding.WelcomeFragment;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity implements OnboardingStepListener {

    // Set this arg to "true" to only show the tutorial steps
    public static final String ARG_ONLY_TUTORIAL = "only_tutorial";

    private NoSwipeViewPager pager;
    private DottedProgressView indicator;
    private ImageButton btnNext;
    private List<Fragment> fragmentList;
    private boolean onlyTutorial;

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
                if (onlyTutorial) {
                    indicator.setCurrentDot(i);
                } else {
                    indicator.setCurrentDot(i - 1);
                }
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

        onlyTutorial = getIntent().getBooleanExtra(ARG_ONLY_TUTORIAL, false);

        if (!onlyTutorial) {
            fragmentList.add(WelcomeFragment.newInstance());
        }
        fragmentList.add(StepFragment.newInstance(R.string.onboarding_step1, R.string.onboarding_step1_title, R.string.onboarding_step1_content, R.drawable.ic_ill_tut01, 0));
        fragmentList.add(StepFragment.newInstance(R.string.onboarding_step2, R.string.onboarding_step2_title, R.string.onboarding_step2_content, R.drawable.ic_ill_tut02, 20));
        fragmentList.add(StepFragment.newInstance(R.string.onboarding_step3, R.string.onboarding_step3_title, R.string.onboarding_step3_content,R.drawable.ic_ill_tut03, 40));
        fragmentList.add(StepFragment.newInstance(R.string.onboarding_step4, R.string.onboarding_step4_title, R.string.onboarding_step4_content, R.drawable.ic_ill_tut04, 60));
        if (!onlyTutorial) {
            fragmentList.add(PrivacyFragment.newInstance());
        }

        // Set adapter
        if (onlyTutorial) {
            indicator.setNumberOfDots(fragmentList.size());
        } else {
            indicator.setNumberOfDots(fragmentList.size() - 2);
        }
        indicator.setCurrentDot(0);
        pager.setOffscreenPageLimit(fragmentList.size());
        pager.setAdapter(new OnboardingPagerAdapter(getSupportFragmentManager()));

        showHideIndicator();
    }

    private void showHideIndicator() {
        if (onlyTutorial) {
            indicator.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
            pager.setAllowedSwipeDirection(NoSwipeViewPager.SwipeDirection.all);
            return;
        }
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
        } else {
            // Done
            finish();
        }
    }

    @Override
    public void onPreviousPressed() {
        if (pager.getCurrentItem() > 0) {
            pager.setCurrentItem(pager.getCurrentItem() - 1, true);
        }
    }

    @Override
    public void onSettingsPressed() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
        finish();
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
