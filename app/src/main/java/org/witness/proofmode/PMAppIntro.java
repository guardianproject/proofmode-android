package org.witness.proofmode;

import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;

import com.github.paolorotolo.appintro.AppIntro;
import com.github.paolorotolo.appintro.AppIntro2;
import com.github.paolorotolo.appintro.AppIntroFragment;

/**
 * Created by n8fr8 on 8/3/16.
 */
public class PMAppIntro extends AppIntro {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportActionBar().hide();

        setFadeAnimation();

        // Instead of fragments, you can also use our default slide
        // Just set a title, description, background and image. AppIntro will do the rest.
       // addSlide(AppIntroFragment.newInstance(getString(R.string.intro1_title), getString(R.string.intro1_desc), R.drawable.oafeature, getResources().getColor(R.color.intro1bg)));
       // addSlide(AppIntroFragment.newInstance(getString(R.string.intro2_title), getString(R.string.intro2_desc), R.drawable.ialogo512, getResources().getColor(R.color.intro2bg)));
       // addSlide(AppIntroFragment.newInstance(getString(R.string.intro3_title), getString(R.string.intro3_desc), R.drawable.ic_nearby_white_24dp, getResources().getColor(R.color.intro3bg)));

        // OPTIONAL METHODS
        // Override bar/separator color.
       // setBarColor(Color.parseColor("#3F51B5"));
       // setSeparatorColor(Color.parseColor("#2196F3"));

        // Hide Skip/Done button.
        showSkipButton(false);
       // setProgressButtonEnabled(false);

    }

    @Override
    public void onSkipPressed(Fragment currentFragment) {
        super.onSkipPressed(currentFragment);
        // Do something when users tap on Skip button.
        finish();
    }

    @Override
    public void onDonePressed(Fragment currentFragment) {
        super.onDonePressed(currentFragment);
        // Do something when users tap on Done button.


        finish();
    }

    @Override
    public void onSlideChanged(@Nullable Fragment oldFragment, @Nullable Fragment newFragment) {
        super.onSlideChanged(oldFragment, newFragment);
        // Do something when the slide changes.
    }
}
