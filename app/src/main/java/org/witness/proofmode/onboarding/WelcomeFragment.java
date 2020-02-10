package org.witness.proofmode.onboarding;

import android.content.Intent;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.witness.proofmode.DataLegendActivity;
import org.witness.proofmode.R;


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

        View btnLearnMore = mRootView.findViewById(R.id.btnLearnMore);
        btnLearnMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), DataLegendActivity.class);
                getActivity().startActivity(intent);
            }
        });

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
