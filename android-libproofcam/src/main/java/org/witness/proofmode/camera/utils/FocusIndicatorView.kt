package org.witness.proofmode.camera.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View

class FocusIndicatorView(context: Context) : View(context) {

    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private var focusPoint: PointF? = null

    fun showFocusIndicator(point: PointF) {
        focusPoint = point
        invalidate()
    }

    fun hideFocusIndicator() {
        focusPoint = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        focusPoint?.let {
            canvas.drawCircle(it.x, it.y, 80f, paint)
            canvas.drawText("Focused", it.x, it.y, textPaint)
        }
    }
}