package com.example.stackcam

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
 * Focus-sweep bokeh. A set of shots at different focus distances gives a depth-from-focus map;
 * everything away from the chosen subject depth is blurred progressively.
 */
object Bokeh {
    fun compose(frames: List<IntArray>, w: Int, h: Int, sx: Float, sy: Float, strength: Int): IntArray {
        val k = frames.size
        val hw = w / 2
        val hh = h / 2
        val hn = hw * hh
        var num = FloatArray(hn)
        var den = FloatArray(hn)
        val lum = FloatArray(hn)
        for (f in 0 until k) {
            val fr = frames[f]
            for (y in 0 until hh) {
                val o = (y * 2) * w
                for (x in 0 until hw) {
                    val p = fr[o + x * 2]
                    lum[y * hw + x] = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) / 256f
                }
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
                num[i] += f * s
                den[i] += s
            }
        }
        num = boxBlurF(boxBlurF(num, hw, hh, 12), hw, hh, 12)
        den = boxBlurF(boxBlurF(den, hw, hh, 12), hw, hh, 12)
        val mid = (k - 1) / 2f
        val depth = FloatArray(hn) { i -> if (den[i] > 1e-3f) num[i] / den[i] else mid }

        val cx = (sx * hw).toInt().coerceIn(0, hw - 1)
        val cy = (sy * hh).toInt().coerceIn(0, hh - 1)
        var acc = 0f
        var cntPx = 0
        for (yy in max(cy - 10, 0)..min(cy + 10, hh - 1)) for (xx in max(cx - 10, 0)..min(cx + 10, hw - 1)) {
            acc += depth[yy * hw + xx]
            cntPx++
        }
        val s0 = acc / max(cntPx, 1)
        val idx0 = s0.roundToInt().coerceIn(0, k - 1)
        val amt = FloatArray(hn) { i -> ((abs(depth[i] - s0) - 0.5f) / 2f).coerceIn(0f, 1f) }

        val sharp = frames[idx0]
        val r3 = max(strength * 3, 3)
        val r2 = max(r3 * 2 / 3, 2)
        val r1 = max(r3 / 3, 1)
        val b1 = blur2(sharp, w, h, r1)
        val b2 = blur2(sharp, w, h, r2)
        val b3 = blur2(sharp, w, h, r3)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ro = (y shr 1) * hw
            for (x in 0 until w) {
                val i = y * w + x
                val a = amt[ro + min(x shr 1, hw - 1)] * 3f
                out[i] = when {
                    a <= 0.001f -> sharp[i]
                    a <= 1f -> mix(sharp[i], b1[i], a)
                    a <= 2f -> mix(b1[i], b2[i], a - 1f)
                    else -> mix(b2[i], b3[i], min(a - 2f, 1f))
                }
            }
        }
        return out
    }

    private fun blur2(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val rr = max(r * 7 / 10, 1)
        return boxBlurArgb(boxBlurArgb(src, w, h, rr), w, h, rr)
    }

    private fun mix(p: Int, q: Int, t: Float): Int {
        val ti = (t * 256f).toInt().coerceIn(0, 256)
        val r = ((((p shr 16) and 255) * (256 - ti)) + (((q shr 16) and 255) * ti)) shr 8
        val g = ((((p shr 8) and 255) * (256 - ti)) + (((q shr 8) and 255) * ti)) shr 8
        val b = (((p and 255) * (256 - ti)) + ((q and 255) * ti)) shr 8
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
