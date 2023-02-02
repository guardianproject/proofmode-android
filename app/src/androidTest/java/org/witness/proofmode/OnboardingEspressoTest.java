package org.witness.proofmode;

import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.witness.proofmode.onboarding.OnboardingActivity;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class OnboardingEspressoTest {

    @Rule
    public ActivityScenarioRule<OnboardingActivity> activityRule =
            new ActivityScenarioRule<>(OnboardingActivity.class);

    @Test
    public void listGoesOverTheFold() {
        //onView(withText("Hello world!")).check(matches(isDisplayed()));

    }

}
