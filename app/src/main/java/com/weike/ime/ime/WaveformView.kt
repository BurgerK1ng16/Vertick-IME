package com.weike.ime.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import com.weike.ime.R
import kotlin.math.abs
import kotlin.math.sin

class WaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.weike_background)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 8f * resources.displayMetrics.density
    }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 850L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun setListening(listening: Boolean) {
        if (listening && !animator.isStarted) animator.start()
        if (!listening && animator.isStarted) animator.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2f
        val spacing = width / 8f
        for (index in 0 until 7) {
            val normalized = abs(sin((index + phase) * 1.6f))
            val halfHeight = height * (0.15f + normalized * 0.25f)
            val x = spacing * (index + 1)
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, paint)
        }
    }
}
