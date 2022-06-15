package org.witness.proofmode.onboarding;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.viewpager.widget.ViewPager;

// Taken from https://stackoverflow.com/questions/19602369/how-to-disable-viewpager-from-swiping-in-one-direction/34076649#34076649
//
public class NoSwipeViewPager extends ViewPager {

	public enum SwipeDirection {
		all, left, right, none;
	}

	private float initialXValue;
	private SwipeDirection direction;

	public NoSwipeViewPager(Context context, AttributeSet attrs) {
		super(context, attrs);
		this.direction = SwipeDirection.all;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (this.IsSwipeAllowed(event)) {
			return super.onTouchEvent(event);
		}

		return false;
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent event) {
		if (this.IsSwipeAllowed(event)) {
			return super.onInterceptTouchEvent(event);
		}

		return false;
	}

	private boolean IsSwipeAllowed(MotionEvent event) {
		if (this.direction == SwipeDirection.all) return true;

		if (direction == SwipeDirection.none)//disable any swipe
			return false;

		if (event.getAction() == MotionEvent.ACTION_DOWN) {
			initialXValue = event.getX();
			return true;
		}

		if (event.getAction() == MotionEvent.ACTION_MOVE) {
			try {
				float diffX = event.getX() - initialXValue;
				if (diffX > 0 && direction == SwipeDirection.right) {
					// swipe from left to right detected
					return false;
				} else if (diffX < 0 && direction == SwipeDirection.left) {
					// swipe from right to left detected
					return false;
				}
			} catch (Exception exception) {
				exception.printStackTrace();
			}
		}

		return true;
	}

	public void setAllowedSwipeDirection(SwipeDirection direction) {
		this.direction = direction;
	}
}