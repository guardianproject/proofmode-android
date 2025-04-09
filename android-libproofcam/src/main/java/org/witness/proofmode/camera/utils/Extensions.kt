package org.witness.proofmode.camera.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PointF
import android.os.Build
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import androidx.annotation.DrawableRes
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.witness.proofmode.camera.adapter.Media
import java.util.concurrent.Executor

fun ImageButton.toggleButton(
    flag: Boolean, rotationAngle: Float, @DrawableRes firstIcon: Int, @DrawableRes secondIcon: Int,
    action: (Boolean) -> Unit
) {
    if (flag) {
        if (rotationY == 0f) rotationY = rotationAngle
        animate().rotationY(0f).apply {
            setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    action(!flag)
                }
            })
        }.duration = 200
        GlobalScope.launch(Dispatchers.Main) {
            delay(100)
            setImageResource(firstIcon)
        }
    } else {
        if (rotationY == rotationAngle) rotationY = 0f
        animate().rotationY(rotationAngle).apply {
            setListener(object : AnimatorListenerAdapter() {

                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    action(!flag)
                }
            })
        }.duration = 200
        GlobalScope.launch(Dispatchers.Main) {
            delay(100)
            setImageResource(secondIcon)
        }
    }
}

fun ViewGroup.circularReveal(button: ImageButton) {
    ViewAnimationUtils.createCircularReveal(
        this,
        button.x.toInt() + button.width / 2,
        button.y.toInt() + button.height / 2,
        0f,
        if (button.context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) this.width.toFloat() else this.height.toFloat()
    ).apply {
        duration = 500
        doOnStart { visibility = VISIBLE }
    }.start()
}

fun ViewGroup.circularClose(button: ImageButton, action: () -> Unit = {}) {
    ViewAnimationUtils.createCircularReveal(
        this,
        button.x.toInt() + button.width / 2,
        button.y.toInt() + button.height / 2,
        if (button.context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) this.width.toFloat() else this.height.toFloat(),
        0f
    ).apply {
        duration = 500
        doOnStart { action() }
        doOnEnd { visibility = GONE }
    }.start()
}

fun View.onWindowInsets(action: (View, WindowInsetsCompat) -> Unit) {
    ViewCompat.requestApplyInsets(this)
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        action(v, insets)
        insets
    }
}

fun Window.fitSystemWindows() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
}

fun Fragment.share(media: Media, title: String = "Share with...") {

    val share = Intent("org.witness.proofmode.action.SHARE_PROOF")
    if (media.isVideo)
        share.type = "video/*"
    else
        share.type = "image/*"
    share.setDataAndType(media.uri, share.type)
    share.putExtra(Intent.EXTRA_STREAM, media.uri)
    share.setPackage(context?.packageName);//this did the trick actually
    share.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // startActivity(Intent.createChooser(share, title))
    startActivity(share)
}

fun Context.share(media: Media, title: String = "Share with...") {
    val share = Intent("org.witness.proofmode.action.SHARE_PROOF")
    if (media.isVideo)
        share.type = "video/*"
    else
        share.type = "image/*"
    share.setDataAndType(media.uri, share.type)
    share.putExtra(Intent.EXTRA_STREAM, media.uri)
    share.setPackage(applicationContext.packageName);//this did the trick actually
    share.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // startActivity(Intent.createChooser(share, title))
    startActivity(share)
}

fun ViewPager2.onPageSelected(action: (Int) -> Unit) {
    registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            action(position)
        }
    })
}

fun Context.mainExecutor(): Executor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    mainExecutor
} else {
    MainExecutor()
}

val Context.layoutInflater: LayoutInflater
    get() = LayoutInflater.from(this)

var View.topMargin: Int
    get() = (this.layoutParams as ViewGroup.MarginLayoutParams).topMargin
    set(value) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = value }
    }

var View.topPadding: Int
    get() = paddingTop
    set(value) {
        updateLayoutParams { setPaddingRelative(paddingStart, value, paddingEnd, paddingBottom) }
    }

var View.bottomMargin: Int
    get() = (this.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
    set(value) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = value }
    }

var View.endMargin: Int
    get() = (this.layoutParams as ViewGroup.MarginLayoutParams).marginEnd
    set(value) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> { marginEnd = value }
    }

var View.startMargin: Int
    get() = (this.layoutParams as ViewGroup.MarginLayoutParams).marginStart
    set(value) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = value }
    }

var View.startPadding: Int
    get() = paddingStart
    set(value) {
        updateLayoutParams { setPaddingRelative(value, paddingTop, paddingEnd, paddingBottom) }
    }

/**
 * Create a scale detector which is a pinch to zoom gesture
 * @param camera - The camera device used to be used for zooming associated with this [PreviewView]
 * in and out
 * @return [ScaleGestureDetector]
 */
fun PreviewView.createPinchDetector(camera: Camera): ScaleGestureDetector {
    // Pinch to zoom detector to change zoom ratio of camera
    val pinchToZoomScaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Retrieve the camera's current zoom state value
                val zoomState = camera.cameraInfo.zoomState.value
                //Retrieve the device camera's max zoom ratio
                val maxZoomRatio = zoomState?.maxZoomRatio ?: 1f

                val minZoomRatio = zoomState?.minZoomRatio ?: 1f
                val currentZoomRatio = zoomState?.zoomRatio ?: 1f
                // calculate new ratio using the detector's scale factor
                val newZoomRatio = currentZoomRatio * detector.scaleFactor

                // Update the zoom ratio
                camera.cameraControl.setZoomRatio(
                    newZoomRatio.coerceIn(
                        minZoomRatio, maxZoomRatio
                    )
                )
                return true
            }

        })

    return pinchToZoomScaleDetector
}


/**
 * Creates a tap gesture to be used for tap to focus
 * @param camera - The camera device used by the [PreviewView]
 * @returns [GestureDetector]
 */
fun PreviewView.createTapGestureDetector(
    camera: Camera,
    lifecycleScope: CoroutineScope
): GestureDetector {
    val viewFinder = this
    val tapGestureDetector = GestureDetector(context, object :
        GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val focusView = FocusIndicatorView(context)
            viewFinder.addView(focusView)
            val factory = viewFinder.meteringPointFactory
            val point = factory.createPoint(event.x, event.y)

            val meteringAction =
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .disableAutoCancel()
                    .build()
            // Some cameras, such as some front Cameras will not support FocusMeteringAction.FLAG_AF
            // so we check if the action is supported
            if (camera.cameraInfo.isFocusMeteringSupported(meteringAction)) {
                lifecycleScope.launch {
                    val focusMeteringResult =
                        camera.cameraControl.startFocusAndMetering(meteringAction)

                                /**
                            .await()
                    if (focusMeteringResult.isFocusSuccessful) {
                        withContext(Dispatchers.Main) {
                            focusView.showFocusIndicator(PointF(event.x, event.y))
                            viewFinder.postDelayed({
                                focusView.hideFocusIndicator()
                            }, 1300)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            viewFinder.postDelayed({
                                focusView.hideFocusIndicator()
                            }, 700)
                        }
                    }**/
                }
            }

            return true
        }

    })
    return tapGestureDetector
}