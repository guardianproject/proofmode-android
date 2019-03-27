package org.witness.proofmode.onboarding;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.witness.proofmode.R;
import org.witness.proofmode.UIHelpers;


public class StepFragment extends Fragment {
    private View mRootView;

    private static final String ARG_STEP = "step";
    private static final String ARG_TITLE = "title";
    private static final String ARG_CONTENT = "content";
    private static final String ARG_ILLUSTRATION = "illustration";

    public static StepFragment newInstance(int idStep, int idTitle, int idContent, int idIllustration) {
        StepFragment f = new StepFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_STEP, idStep);
        args.putInt(ARG_TITLE, idTitle);
        args.putInt(ARG_CONTENT, idContent);
        args.putInt(ARG_ILLUSTRATION, idIllustration);
        f.setArguments(args);
        return f;
    }

    public StepFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.onboarding_step, container, false);

        TextView tv = mRootView.findViewById(R.id.step);
        tv.setText(getArguments().getInt(ARG_STEP, 0));
        tv = mRootView.findViewById(R.id.title);
        tv.setText(getArguments().getInt(ARG_TITLE, 0));
        tv = mRootView.findViewById(R.id.content);
        tv.setText(getArguments().getInt(ARG_CONTENT, 0));
        int illustration = getArguments().getInt(ARG_ILLUSTRATION, 0);
        if (illustration != 0 && mRootView.findViewById(R.id.illustration) != null) {
            //UIHelpers.populateContainerWithSVG(mRootView, illustration, R.id.illustration);
        }

        View btnPrevious = mRootView.findViewById(R.id.btnPrevious);
        btnPrevious.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() instanceof OnboardingStepListener) {
                    ((OnboardingStepListener) getActivity()).onPreviousPressed();
                }
            }
        });

        return mRootView;
    }
}
