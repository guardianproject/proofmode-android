package org.witness.proofmode.onboarding

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

// Taken from https://stackoverflow.com/questions/19602369/how-to-disable-viewpager-from-swiping-in-one-direction/34076649#34076649
//
class NoSwipeViewPager(context: Context, attrs: AttributeSet?) : ViewPager(
    context, attrs
) {
    enum class SwipeDirection {
        ALL, LEFT, RIGHT, NONE
    }

    private var initialXValue = 0f
    private var direction: SwipeDirection

    init {
        direction = SwipeDirection.ALL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (isSwipeAllowed(event)) {
            super.onTouchEvent(event)
        } else false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return if (isSwipeAllowed(event)) {
            super.onInterceptTouchEvent(event)
        } else false
    }

    private fun isSwipeAllowed(event: MotionEvent): Boolean {
        if (direction == SwipeDirection.ALL) return true
        if (direction == SwipeDirection.NONE) //disable any swipe
            return false
        if (event.action == MotionEvent.ACTION_DOWN) {
            initialXValue = event.x
            return true
        }
        if (event.action == MotionEvent.ACTION_MOVE) {
            try {
                val diffX = event.x - initialXValue
                if (diffX > 0 && direction == SwipeDirection.RIGHT) {
                    // swipe from left to right detected
                    return false
                } else if (diffX < 0 && direction == SwipeDirection.LEFT) {
                    // swipe from right to left detected
                    return false
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
        }
        return true
    }

    fun setAllowedSwipeDirection(direction: SwipeDirection) {
        this.direction = direction
    }
}