package org.witness.proofmode.onboarding;

import android.graphics.Picture;
import android.graphics.drawable.PictureDrawable;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.caverock.androidsvg.RenderOptions;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGImageView;
import com.caverock.androidsvg.SVGParseException;

import org.witness.proofmode.R;

import java.io.IOException;


public class StepFragment extends Fragment {

    private static final String ARG_STEP = "step";
    private static final String ARG_TITLE = "title";
    private static final String ARG_CONTENT = "content";
    private static final String ARG_ILLUSTRATION = "illustration";
    private static final String ARG_ILLUSTRATION_OFFSET = "illustration_offset";

    private View mRootView;
    private Thread loaderThread;

    public static StepFragment newInstance(int idStep, int idTitle, int idContent, String idIllustration, int illustrationOffset) {
        StepFragment f = new StepFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_STEP, idStep);
        args.putInt(ARG_TITLE, idTitle);
        args.putInt(ARG_CONTENT, idContent);
        args.putString(ARG_ILLUSTRATION, idIllustration);
        args.putInt(ARG_ILLUSTRATION_OFFSET, illustrationOffset);
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
        String illustration = getArguments().getString(ARG_ILLUSTRATION);
        if (!TextUtils.isEmpty(illustration) && mRootView.findViewById(R.id.illustration) != null) {
            SVGImageView imageView = (SVGImageView)mRootView.findViewById(R.id.illustration);
            imageView.setImageAsset(illustration);
            //loadSVG(imageView, illustration);
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

    private void loadSVG(final SVGImageView imageView, final String illustration) {
            loaderThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final SVG svg = SVG.getFromAsset(getResources().getAssets(), illustration);
                        if (svg == null)
                            return;
                        Picture picture = svg.renderToPicture(new RenderOptions());
                        final PictureDrawable drawable = new PictureDrawable(picture);

                        imageView.post(new Runnable() {
                            @Override
                            public void run() {
                                imageView.setImageDrawable(drawable);
                            }
                        });
                    } catch (SVGParseException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            loaderThread.start();
    }
}
