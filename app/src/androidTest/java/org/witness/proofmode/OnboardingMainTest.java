package org.witness.proofmode;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.witness.proofmode.onboarding.OnboardingActivity;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class OnboardingMainTest {

    @Rule
    public ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void listGoesOverTheFold() {
        //onView(withText("Hello world!")).check(matches(isDisplayed()));

    }

}
