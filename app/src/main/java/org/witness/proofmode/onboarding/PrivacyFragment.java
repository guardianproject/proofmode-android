package org.witness.proofmode.onboarding;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.witness.proofmode.R;
import org.witness.proofmode.UIHelpers;


public class PrivacyFragment extends Fragment {
    private View mRootView;

    public static PrivacyFragment newInstance() {
        PrivacyFragment f = new PrivacyFragment();
        return f;
    }

    public PrivacyFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.onboarding_privacy, container, false);

        View btnPrevious = mRootView.findViewById(R.id.btnPrevious);
        btnPrevious.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() instanceof OnboardingStepListener) {
                    ((OnboardingStepListener) getActivity()).onPreviousPressed();
                }
            }
        });

        View btnContinue = mRootView.findViewById(R.id.btnContinue);
        btnContinue.setOnClickListener(new View.OnClickListener() {
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
