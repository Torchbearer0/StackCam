package com.example.stackcam

import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Rotated(val px: IntArray, val w: Int, val h: Int)

/** Rotates ARGB pixels clockwise by 0/90/180/270 degrees. */
object Rot {
    fun cw(src: IntArray, w: Int, h: Int, deg: Int): Rotated {
        when (deg % 360) {
            90 -> {
                val out = IntArray(src.size)
                for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
                return Rotated(out, h, w)
            }
            180 -> {
                val out = IntArray(src.size)
                for (y in 0 until h) for (x in 0 until w) out[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
                return Rotated(out, w, h)
            }
            270 -> {
                val out = IntArray(src.size)
                for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = src[y * w + x]
                return Rotated(out, h, w)
            }
        }
        return Rotated(src, w, h)
    }
}

fun boxBlurF(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(src.size)
    val out = FloatArray(src.size)
    val win = (2 * r + 1).toFloat()
    for (y in 0 until h) {
        val row = y * w
        var sum = 0f
        for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            tmp[row + x] = sum / win
            sum += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
        }
    }
    for (x in 0 until w) {
        var sum = 0f
        for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) {
            out[y * w + x] = sum / win
            sum += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
        }
    }
    return out
}

fun boxBlurArgb(src: IntArray, w: Int, h: Int, r: Int): IntArray {
    val tmp = IntArray(src.size)
    val out = IntArray(src.size)
    val win = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var sr = 0
        var sg = 0
        var sb = 0
        for (x in -r..r) {
            val p = src[row + x.coerceIn(0, w - 1)]
            sr += (p shr 16) and 255
            sg += (p shr 8) and 255
            sb += p and 255
        }
        for (x in 0 until w) {
            tmp[row + x] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
            val a = src[row + min(x + r + 1, w - 1)]
            val b = src[row + max(x - r, 0)]
            sr += ((a shr 16) and 255) - ((b shr 16) and 255)
            sg += ((a shr 8) and 255) - ((b shr 8) and 255)
            sb += (a and 255) - (b and 255)
        }
    }
    for (x in 0 until w) {
        var sr = 0
        var sg = 0
        var sb = 0
        for (y in -r..r) {
            val p = tmp[y.coerceIn(0, h - 1) * w + x]
            sr += (p shr 16) and 255
            sg += (p shr 8) and 255
            sb += p and 255
        }
        for (y in 0 until h) {
            out[y * w + x] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
            val a = tmp[min(y + r + 1, h - 1) * w + x]
            val b = tmp[max(y - r, 0) * w + x]
            sr += ((a shr 16) and 255) - ((b shr 16) and 255)
            sg += ((a shr 8) and 255) - ((b shr 8) and 255)
            sb += (a and 255) - (b and 255)
        }
    }
    return out
}

/**
 * Sky stacker: aligns frames on the stars (Earth's rotation moves them), averages, then stretches.
 * The ground is not tracked, so it smears slightly in long stacks.
 */
class AstroStacker(private val w: Int, private val h: Int, private val align: Boolean) {
    private val n = w * h
    private val sum = IntArray(n * 3)
    private val cnt = ShortArray(n)
    private val hw = w / 2
    private val hh = h / 2
    private val lum = ByteArray(hw * hh)
    private var starX = IntArray(0)
    private var starY = IntArray(0)
    private var curDx = 0
    private var curDy = 0
    var frames = 0
        private set

    private fun halfLum(src: IntArray) {
        for (y in 0 until hh) {
            val o = (y * 2) * w
            for (x in 0 until hw) {
                val p = src[o + x * 2]
                lum[y * hw + x] = ((((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8).toByte()
            }
        }
    }

    private fun findStars() {
        var s = 0L
        var s2 = 0L
        for (v in lum) {
            val a = v.toInt() and 255
            s += a
            s2 += (a * a).toLong()
        }
        val m = s.toDouble() / lum.size
        val sd = sqrt(max(s2.toDouble() / lum.size - m * m, 0.0))
        val thr = min(m + 5.0 * sd, 250.0).toInt()
        val cell = 8
        val found = ArrayList<IntArray>()
        var cy = 0
        while (cy + cell <= hh) {
            var cx = 0
            while (cx + cell <= hw) {
                var bv = -1
                var bx = 0
                var by = 0
                for (yy in cy until cy + cell) for (xx in cx until cx + cell) {
                    val v = lum[yy * hw + xx].toInt() and 255
                    if (v > bv) { bv = v; bx = xx; by = yy }
                }
                if (bv > thr) found.add(intArrayOf(bx, by, bv))
                cx += cell
            }
            cy += cell
        }
        found.sortByDescending { it[2] }
        val k = min(found.size, 500)
        starX = IntArray(k) { found[it][0] }
        starY = IntArray(k) { found[it][1] }
    }

    private fun estimate() {
        if (starX.size < 20) return
        var best = -1L
        var bdx = curDx
        var bdy = curDy
        val range = 6
        for (dy in curDy - range..curDy + range) for (dx in curDx - range..curDx + range) {
            var sc = 0L
            for (k in starX.indices) {
                val x = starX[k] + dx
                val y = starY[k] + dy
                if (x < 0 || y < 0 || x >= hw || y >= hh) continue
                sc += (lum[y * hw + x].toInt() and 255).toLong()
            }
            if (sc > best) { best = sc; bdx = dx; bdy = dy }
        }
        curDx = bdx
        curDy = bdy
    }

    fun add(src: IntArray) {
        if (align) {
            halfLum(src)
            if (frames == 0) findStars() else estimate()
        }
        val dx = curDx * 2
        val dy = curDy * 2
        for (y in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= h) continue
            val row = y * w
            val srow = sy * w
            for (x in 0 until w) {
                val sx = x + dx
                if (sx < 0 || sx >= w) continue
                val p = src[srow + sx]
                val i = row + x
                val k = i * 3
                sum[k] += (p shr 16) and 255
                sum[k + 1] += (p shr 8) and 255
                sum[k + 2] += p and 255
                cnt[i] = (cnt[i] + 1).toShort()
            }
        }
        frames++
    }

    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return hist.size - 1
    }

    fun result(stretch: Boolean): IntArray {
        val out = IntArray(n)
        if (!stretch) {
            for (i in 0 until n) {
                val c = max(cnt[i].toInt(), 1)
                val k = i * 3
                out[i] = (0xFF shl 24) or ((sum[k] / c) shl 16) or ((sum[k + 1] / c) shl 8) or (sum[k + 2] / c)
            }
            return out
        }
        val hist = Array(3) { IntArray(4096) }
        var total = 0
        var i = 0
        while (i < n) {
            val c = max(cnt[i].toInt(), 1)
            val k = i * 3
            for (ch in 0 until 3) {
                val v = min((sum[k + ch] * 16) / c, 4095)
                hist[ch][v]++
            }
            total++
            i += 2
        }
        val black = IntArray(3) { percentile(hist[it], total, 0.20) }
        var white = 0
        for (ch in 0 until 3) white = max(white, percentile(hist[ch], total, 0.999))
        val luts = Array(3) { ch ->
            val span = max(white - black[ch], 120).toDouble()
            IntArray(4096) { v ->
                val t = ((v - black[ch]) / span).coerceIn(0.0, 1.0)
                (Math.pow(t, 0.55) * 255.0).toInt()
            }
        }
        for (j in 0 until n) {
            val c = max(cnt[j].toInt(), 1)
            val k = j * 3
            var r = luts[0][min((sum[k] * 16) / c, 4095)]
            var g = luts[1][min((sum[k + 1] * 16) / c, 4095)]
            var b = luts[2][min((sum[k + 2] * 16) / c, 4095)]
            val l = (r * 77 + g * 150 + b * 29) shr 8
            r = (l + (((r - l) * 13) shr 3)).coerceIn(0, 255)
            g = (l + (((g - l) * 13) shr 3)).coerceIn(0, 255)
            b = (l + (((b - l) * 13) shr 3)).coerceIn(0, 255)
            out[j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }
}

/**
 * Focus-sweep bokeh, streaming version. Shots at different focus distances give a depth-from-focus map
 * (in diopters). The first shot is the sharp subject (autofocus on the tapped spot); everything whose
 * depth differs from the subject is blurred progressively. Only the subject frame is kept at full size.
 */
class BokehAccum(private val w: Int, private val h: Int) {
    private val hw = w / 2
    private val hh = h / 2
    private val hn = hw * hh
    private var num = FloatArray(hn)
    private var den = FloatArray(hn)
    private val lum = FloatArray(hn)
    private var subject: IntArray? = null
    private var d0 = 0f
    var shots = 0
        private set

    fun add(img: Image, diopter: Float, isSubject: Boolean, scratch: IntArray) {
        val yp = img.planes[0]
        val buf = yp.buffer
        val rs = yp.rowStride
        for (y in 0 until hh) {
            val o = (y * 2) * rs
            for (x in 0 until hw) lum[y * hw + x] = (buf.get(o + x * 2).toInt() and 255).toFloat()
        }
        var lap = FloatArray(hn)
        for (y in 1 until hh - 1) for (x in 1 until hw - 1) {
            val i = y * hw + x
            val l = abs(4f * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - hw] - lum[i + hw])
            lap[i] = l * l
        }
        lap = boxBlurF(lap, hw, hh, 3)
        for (i in 0 until hn) {
            val s = lap[i]
            num[i] += diopter * s
            den[i] += s
        }
        if (isSubject) {
            Yuv.toArgb(img, scratch)
            subject = scratch.copyOf()
            d0 = diopter
        }
        shots++
    }

    private fun blur2(src: IntArray, bw: Int, bh: Int, r: Int): IntArray {
        val rr = max(r * 7 / 10, 1)
        return boxBlurArgb(boxBlurArgb(src, bw, bh, rr), bw, bh, rr)
    }

    fun result(strength: Int): IntArray {
        val subj = subject ?: throw IllegalStateException("No subject frame")
        val sc = w / 1920f
        val rr = max((12 * sc).toInt(), 6)
        num = boxBlurF(boxBlurF(num, hw, hh, rr), hw, hh, rr)
        den = boxBlurF(boxBlurF(den, hw, hh, rr), hw, hh, rr)
        val tol = 0.08f + 0.05f * d0
        val span = 0.30f + 0.30f * d0
        val amt = FloatArray(hn) { i ->
            val depth = if (den[i] > 1e-3f) num[i] / den[i] else d0
            ((abs(depth - d0) - tol) / span).coerceIn(0f, 1f)
        }
        val half = IntArray(hn)
        for (y in 0 until hh) {
            for (x in 0 until hw) {
                val i = (y * 2) * w + x * 2
                val a = subj[i]
                val b = subj[i + 1]
                val c = subj[i + w]
                val d = subj[i + w + 1]
                val r = (((a shr 16) and 255) + ((b shr 16) and 255) + ((c shr 16) and 255) + ((d shr 16) and 255)) shr 2
                val g = (((a shr 8) and 255) + ((b shr 8) and 255) + ((c shr 8) and 255) + ((d shr 8) and 255)) shr 2
                val bl = ((a and 255) + (b and 255) + (c and 255) + (d and 255)) shr 2
                half[y * hw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        val r3 = max((strength * 3 * sc / 2f).toInt(), 2)
        val r2 = max(r3 * 2 / 3, 2)
        val r1 = max(r3 / 3, 1)
        val b1 = blur2(half, hw, hh, r1)
        val b2 = blur2(half, hw, hh, r2)
        val b3 = blur2(half, hw, hh, r3)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ho = min(y shr 1, hh - 1) * hw
            for (x in 0 until w) {
                val hi = ho + min(x shr 1, hw - 1)
                val a = amt[hi] * 3f
                val i = y * w + x
                val sharp = subj[i]
                out[i] = when {
                    a <= 0.001f -> sharp
                    a <= 1f -> mixArgb(sharp, b1[hi], a)
                    a <= 2f -> mixArgb(b1[hi], b2[hi], a - 1f)
                    else -> mixArgb(b2[hi], b3[hi], min(a - 2f, 1f))
                }
            }
        }
        return out
    }
}
