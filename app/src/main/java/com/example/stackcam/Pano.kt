package com.example.stackcam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal panorama stitcher working on upright frames. Each accepted frame is registered against
 * the previous accepted one (coarse then fine search) and blended into a growing canvas.
 */
class PanoStitcher(private val uw: Int, private val uh: Int) {
    private val maxLen = uw * 6
    private val margin = uh * 15 / 100
    private val chh = uh + 2 * margin
    private val canvas = IntArray(maxLen * chh)
    private var px = maxLen / 2 - uw / 2
    private var py = margin
    private var minX = px
    private var maxX = px
    private var yTop = py
    private var yBot = py + uh
    private var prevLow: ByteArray? = null
    private var prevHalf: ByteArray? = null
    var frames = 0
        private set

    val painted: Int get() = maxX - minX
    val limit: Int get() = maxLen

    private fun paste(f: IntArray, nx: Int, ny: Int, oldMin: Int, oldMax: Int, right: Boolean) {
        val overlapLo = if (right) nx else oldMin
        val overlapHi = if (right) oldMax else nx + uw
        val seam = (overlapLo + overlapHi) / 2
        val band = max(8, (overlapHi - overlapLo) * 15 / 100)
        for (fx in 0 until uw) {
            val cx = nx + fx
            val a: Float = if (cx < oldMin || cx >= oldMax) {
                1f
            } else if (right) {
                ((cx - (seam - band)).toFloat() / (2 * band)).coerceIn(0f, 1f)
            } else {
                (((seam + band) - cx).toFloat() / (2 * band)).coerceIn(0f, 1f)
            }
            if (a <= 0f) continue
            for (fy in 0 until uh) {
                val idx = (ny + fy) * maxLen + cx
                val nw = f[fy * uw + fx]
                val old = canvas[idx]
                canvas[idx] = if ((old ushr 24) == 0 || a >= 1f) nw else mixArgb(old, nw, a)
            }
        }
    }

    /** Returns 0 accepted, 1 not enough movement, 2 moving too fast, 3 canvas full. */
    fun add(f: IntArray): Int {
        val low = Align.lumLow(f, uw, uh, 8)
        val half = Align.lumLow(f, uw, uh, 2)
        val pl = prevLow
        val ph = prevHalf
        if (pl == null || ph == null) {
            for (fy in 0 until uh) System.arraycopy(f, fy * uw, canvas, (py + fy) * maxLen + px, uw)
            minX = px
            maxX = px + uw
            prevLow = low
            prevHalf = half
            frames = 1
            return 0
        }
        val lw = uw / 8
        val lh = uh / 8
        val c = Align.shift(pl, low, lw, lh, (lw * 0.66f).toInt(), max(lh / 10, 4), 0, 0, 3)
        val hw = uw / 2
        val hh = uh / 2
        val r = Align.shift(ph, half, hw, hh, 6, 6, c[0] * 4, c[1] * 4, 4)
        val dx = r[0] * 2
        val dy = r[1] * 2
        val move = abs(dx)
        if (move < uw * 12 / 100) return 1
        if (move > uw * 65 / 100) return 2
        val nx = px - dx
        val ny = py - dy
        if (nx < 0 || nx + uw > maxLen || ny < 0 || ny + uh > chh) return 3
        val right = dx < 0
        val oldMin = minX
        val oldMax = maxX
        if (right && nx + uw > oldMax) {
            paste(f, nx, ny, oldMin, oldMax, true)
            maxX = nx + uw
            minX = min(minX, nx)
        } else if (!right && nx < oldMin) {
            paste(f, nx, ny, oldMin, oldMax, false)
            minX = nx
            maxX = max(maxX, nx + uw)
        }
        px = nx
        py = ny
        prevLow = low
        prevHalf = half
        frames++
        yTop = max(yTop, ny)
        yBot = min(yBot, ny + uh)
        return 0
    }

    fun finish(): Rotated? {
        if (frames < 2) return null
        val ow = maxX - minX
        val oh = yBot - yTop
        if (ow < uw + 10 || oh < uh / 2) return null
        val out = IntArray(ow * oh)
        for (yy in 0 until oh) System.arraycopy(canvas, (yTop + yy) * maxLen + minX, out, yy * ow, ow)
        return Rotated(out, ow, oh)
    }
}
