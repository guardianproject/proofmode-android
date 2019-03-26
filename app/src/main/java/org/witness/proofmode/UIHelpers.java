package org.witness.proofmode;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGImageView;
import com.caverock.androidsvg.SVGParseException;

public class UIHelpers
{
	public static int dpToPx(int dp, Context ctx)
	{
		Resources r = ctx.getResources();
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, r.getDisplayMetrics());
	}

	public static void hideSoftKeyboard(Activity activity)
	{
		InputMethodManager inputMethodManager = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
		View view = activity.getCurrentFocus();
		if (view != null)
			inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
	}

	public static void populateContainerWithSVG(View rootView, int idSVG, int idContainer) {
		try {
			SVG svg = SVG.getFromResource(rootView.getContext(), idSVG);

			SVGImageView svgImageView = new SVGImageView(rootView.getContext());
			svgImageView.setFocusable(false);
			svgImageView.setFocusableInTouchMode(false);
			svgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
			svgImageView.setSVG(svg);
			svgImageView.setLayoutParams(new ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT));
			ViewGroup layout = (ViewGroup) rootView.findViewById(idContainer);
			layout.addView(svgImageView);
		} catch (SVGParseException e) {
			e.printStackTrace();
		}
	}
}
