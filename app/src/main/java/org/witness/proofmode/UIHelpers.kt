package org.witness.proofmode

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGImageView
import com.caverock.androidsvg.SVGParseException

object UIHelpers {
    @JvmStatic
	fun dpToPx(dp: Int, ctx: Context): Int {
        val r = ctx.resources
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            r.displayMetrics
        ).toInt()
    }

    fun hideSoftKeyboard(activity: Activity) {
        val inputMethodManager =
            activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        val view = activity.currentFocus
        if (view != null) inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun populateContainerWithSVG(rootView: View, offsetX: Int, idSVG: Int, idContainer: Int) {
        try {
            val svg = SVG.getFromResource(rootView.context, idSVG)
            if (offsetX != 0) {
                val viewBox = svg.documentViewBox
                viewBox.offset(offsetX.toFloat(), 0f)
                svg.setDocumentViewBox(viewBox.left, viewBox.top, viewBox.width(), viewBox.height())
            }
            val svgImageView = SVGImageView(rootView.context)
            svgImageView.isFocusable = false
            svgImageView.isFocusableInTouchMode = false
            svgImageView.scaleType = ImageView.ScaleType.CENTER_CROP
            svgImageView.setSVG(svg)
            svgImageView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val layout = rootView.findViewById<View>(idContainer) as ViewGroup
            layout.addView(svgImageView)
        } catch (e: SVGParseException) {
            e.printStackTrace()
        }
    }
}