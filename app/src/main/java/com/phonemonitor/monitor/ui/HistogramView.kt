package com.phonemonitor.monitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 极简亮度直方图叠层。
 * 数据由渲染器每 N 帧通过 glReadPixels 计算后回调到主线程。
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var histogram: IntArray? = null

    private val bgPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 235, 235, 235)
        strokeWidth = 1f
    }
    private val guidePaint = Paint().apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1f
    }

    fun setHistogram(hist: IntArray) {
        histogram = hist
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val hist = histogram ?: return
        val maxCount = (hist.maxOrNull() ?: 0).coerceAtLeast(1)

        for (i in 0 until 256) {
            val x = i / 256f * w
            val y = h - (hist[i].toFloat() / maxCount) * (h - 3f)
            canvas.drawLine(x, h, x, y, barPaint)
        }

        // 25% / 50% / 75% 参考线
        for (q in 1..3) {
            val x = q / 4f * w
            canvas.drawLine(x, 0f, x, h, guidePaint)
        }
    }
}
