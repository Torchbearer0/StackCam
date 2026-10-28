package com.example.stackcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import kotlin.math.min

val ACCENT: Int = Color.parseColor("#FFD54F")
private val RED: Int = Color.parseColor("#E53935")

fun roundRect(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

/** Shutter: white ring + disc. look 0 = photo, 1 = video (red), 2 = stop square. Progress draws an arc. */
class ShutterView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var look = 0
    private var progress = 0f
    private val rect = RectF()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeWidth = 4f * d
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeWidth = 5f * d
        strokeCap = Paint.Cap.ROUND
        color = ACCENT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(newLook: Int, newProgress: Float, enabled: Boolean) {
        look = newLook
        progress = newProgress
        isEnabled = enabled
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6f * d
        val a = if (isEnabled) 255 else 110
        ring.color = Color.WHITE
        ring.alpha = a
        c.drawCircle(cx, cy, r, ring)
        if (progress > 0f) {
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, -90f, 360f * progress, false, arc)
        }
        when (look) {
            0 -> {
                fill.color = Color.WHITE
                fill.alpha = a
                c.drawCircle(cx, cy, r - 9f * d, fill)
            }
            1 -> {
                fill.color = RED
                fill.alpha = a
                c.drawCircle(cx, cy, r - 9f * d, fill)
            }
            else -> {
                fill.color = RED
                fill.alpha = a
                val s = r * 0.42f
                rect.set(cx - s, cy - s, cx + s, cy + s)
                c.drawRoundRect(rect, 8f * d, 8f * d, fill)
            }
        }
    }
}

/** Draws the tap ring and an optional rule-of-thirds grid on top of the viewfinder. */
class OverlayView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var grid = false
    private var rx = -1f
    private var ry = -1f
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        color = ACCENT
        strokeWidth = 3f * d
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1f * d
    }
    private val hide = Runnable {
        rx = -1f
        invalidate()
    }

    fun ring(x: Float, y: Float) {
        rx = x
        ry = y
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, 1500)
    }

    override fun onDraw(c: Canvas) {
        if (grid) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
            c.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
            c.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
            c.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)
        }
        if (rx >= 0f) c.drawCircle(rx, ry, 36f * d, ringPaint)
    }
}
