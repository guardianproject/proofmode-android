package org.witness.proofmode.onboarding

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import org.witness.proofmode.R
import org.witness.proofmode.UIHelpers

class DottedProgressView : View {
    private var mPaint: Paint? = null
    private var mGravity = 0
    private var mDistance // Distance between dots
            = 0
    private var mRadius // Size of a dot
            = 0
    private var mColor // Dot color
            = 0
    private var mRimColor // Dot rim color
            = 0
    private var mColorCurrent // Dot color
            = 0
    private var mRimColorCurrent // Dot rim color
            = 0
    private var mNumDots // Total number of dots
            = 0
    private var mCurrentDot // Current dot
            = 0
    private var mHideIfOnlyOne = true

    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init(attrs)
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context?) : super(context) {
        init(null)
    }

    private fun init(attrs: AttributeSet?) {
        // Set defaults
        mGravity = Gravity.CENTER
        mDistance = UIHelpers.dpToPx(21, context)
        mRadius = UIHelpers.dpToPx(5, context)
        mColor = Color.TRANSPARENT
        mRimColor = -0x777778
        mColorCurrent = -0x777778
        mRimColorCurrent = -0x777778
        mHideIfOnlyOne = true
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.DottedProgressView)
            mGravity = a.getInt(R.styleable.DottedProgressView_android_gravity, mGravity)
            mDistance =
                a.getDimensionPixelSize(R.styleable.DottedProgressView_dot_distance, mDistance)
            mRadius = a.getDimensionPixelSize(R.styleable.DottedProgressView_dot_radius, mRadius)
            mColor = a.getColor(R.styleable.DottedProgressView_dot_color, mColor)
            mRimColor = a.getColor(R.styleable.DottedProgressView_dot_rim_color, mRimColor)
            mColorCurrent =
                a.getColor(R.styleable.DottedProgressView_dot_color_current, mColorCurrent)
            mRimColorCurrent =
                a.getColor(R.styleable.DottedProgressView_dot_rim_color_current, mRimColorCurrent)
            mNumDots = a.getInt(R.styleable.DottedProgressView_dot_total, 3)
            mCurrentDot = a.getInt(R.styleable.DottedProgressView_dot_current, 1)
            //		mHideIfOnlyOne = a.getBoolean(R.styleable.DottedProgressView_hide_if_only_one, mHideIfOnlyOne);
            a.recycle()
        }
        mPaint = Paint()
        mPaint!!.isAntiAlias = true
        mPaint!!.color = mColor
        mPaint!!.strokeWidth = 1f
        setWillNotDraw(false)
    }

    /*
	 * Get the total number of dots for the view.
	 *//*
	 * Set the total number of dots for the view.
	 */
    var numberOfDots: Int
        get() = mNumDots
        set(dots) {
            if (dots != mNumDots) {
                mNumDots = dots
                invalidate()
            }
        }

    /*
	 * Get the current dot index for the view (0-indexed).
	 *//*
	 * Set the current dot index for the view (0-indexed).
	 */
    var currentDot: Int
        get() = mCurrentDot
        set(index) {
            if (index != mCurrentDot) {
                mCurrentDot = index
                invalidate()
            }
        }

    /*
	 * Calculate the number of dots we can fit in the screen space we are given.
	 */
    private val maxNumberOfDots: Int
        private get() {
            if (mRadius < 1 || mDistance <= 2 * mRadius) return 0
            var w = this.width
            w -= 2 * mRadius
            val n = 1 + w / mDistance
            return if (n < 1) 0 else n
        }

    private fun getDotXPosition(dot: Int, numToDraw: Int): Int {
        return if (mGravity == Gravity.END) {
            var x = width - mDistance
            val widthToDraw = (numToDraw - 1) * mDistance
            x -= widthToDraw
            x += dot * mDistance
            x
        } else {
            var x = width / 2
            val widthToDraw = (numToDraw - 1) * mDistance
            x -= widthToDraw / 2
            x += dot * mDistance
            x
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mNumDots <= 1 && mHideIfOnlyOne) {
            return
        }
        val sc = canvas.save()
        var numDotsToDraw = mNumDots
        var currentDot = mCurrentDot

        // If all dots don't fit, show only the remaining pages (see design
        // document)
        //
        val dotsPerPage = maxNumberOfDots
        if (dotsPerPage < mNumDots) {
            // Get the current "page" for the current dot
            val page = (mCurrentDot.toFloat() / (dotsPerPage - 1)).toInt()
            currentDot = (mCurrentDot.toFloat() % (dotsPerPage - 1)).toInt()
            numDotsToDraw = Math.min(dotsPerPage, 1 + mNumDots - page * dotsPerPage)
        }

        // Do the draw
        for (i in 0 until numDotsToDraw) {
            if (i == currentDot) {
                mPaint!!.style = Paint.Style.FILL
                mPaint!!.color = mColorCurrent
                canvas.drawCircle(
                    getDotXPosition(i, numDotsToDraw).toFloat(),
                    (height / 2).toFloat(),
                    mRadius.toFloat(),
                    mPaint!!
                )
                mPaint!!.style = Paint.Style.STROKE
                mPaint!!.color = mRimColorCurrent
                canvas.drawCircle(
                    getDotXPosition(i, numDotsToDraw).toFloat(),
                    (height / 2).toFloat(),
                    mRadius.toFloat(),
                    mPaint!!
                )
            } else {
                mPaint!!.style = Paint.Style.FILL
                mPaint!!.color = mColor
                canvas.drawCircle(
                    getDotXPosition(i, numDotsToDraw).toFloat(),
                    (height / 2).toFloat(),
                    mRadius.toFloat(),
                    mPaint!!
                )
                mPaint!!.style = Paint.Style.STROKE
                mPaint!!.color = mRimColor
                canvas.drawCircle(
                    getDotXPosition(i, numDotsToDraw).toFloat(),
                    (height / 2).toFloat(),
                    mRadius.toFloat(),
                    mPaint!!
                )
            }
        }
        canvas.restoreToCount(sc)
    }
}