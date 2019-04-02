package org.witness.proofmode.onboarding;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.witness.proofmode.R;
import org.witness.proofmode.UIHelpers;


public class WelcomeFragment extends Fragment {
    private View mRootView;

    public static WelcomeFragment newInstance() {
        WelcomeFragment f = new WelcomeFragment();
        Bundle args = new Bundle();
        f.setArguments(args);
        return f;
    }

    public WelcomeFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.onboarding_welcome, container, false);

        View btnNext = mRootView.findViewById(R.id.btnNext);
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() instanceof OnboardingStepListener) {
                    ((OnboardingStepListener) getActivity()).onNextPressed();
                }
            }
        });

        return mRootView;
    }
}
