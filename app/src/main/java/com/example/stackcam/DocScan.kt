package com.example.stackcam

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Finds the paper in an upright photo, flattens it, evens out the lighting and optionally makes it black and white. */
object DocScan {

    private fun bilinear(src: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xf = x.coerceIn(0f, (w - 1).toFloat())
        val yf = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xf.toInt()
        val y0 = yf.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = xf - x0
        val fy = yf - y0
        val top = mixArgb(src[y0 * w + x0], src[y0 * w + x1], fx)
        val bot = mixArgb(src[y1 * w + x0], src[y1 * w + x1], fx)
        return mixArgb(top, bot, fy)
    }

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    fun process(src: IntArray, w: Int, h: Int, bw: Boolean): Rotated {
        val s = 4
        val lw = w / s
        val lh = h / s
        val ln = lw * lh
        val lum = Align.lumLow(src, w, h, s)

        // Otsu threshold: paper is assumed brighter than what is around it
        val hist = IntArray(256)
        for (v in lum) hist[v.toInt() and 255]++
        val total = ln.toDouble()
        var sumAll = 0.0
        for (i in 0 until 256) sumAll += i * hist[i].toDouble()
        var wB = 0.0
        var sumB = 0.0
        var best = -1.0
        var thr = 128
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }

        // largest bright connected region, remembering its four extreme corners
        val visited = BooleanArray(ln)
        val queue = IntArray(ln)
        var bestSize = 0
        var cTL = 0
        var cTR = 0
        var cBR = 0
        var cBL = 0
        for (start in 0 until ln) {
            if (visited[start] || (lum[start].toInt() and 255) <= thr) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minS = Int.MAX_VALUE
            var maxS = Int.MIN_VALUE
            var minD = Int.MAX_VALUE
            var maxD = Int.MIN_VALUE
            var tl = start
            var br = start
            var tr = start
            var bl = start
            while (head < tail) {
                val i = queue[head++]
                val x = i % lw
                val y = i / lw
                val sSum = x + y
                val dDiff = x - y
                if (sSum < minS) { minS = sSum; tl = i }
                if (sSum > maxS) { maxS = sSum; br = i }
                if (dDiff > maxD) { maxD = dDiff; tr = i }
                if (dDiff < minD) { minD = dDiff; bl = i }
                if (x > 0 && !visited[i - 1] && (lum[i - 1].toInt() and 255) > thr) { visited[i - 1] = true; queue[tail++] = i - 1 }
                if (x < lw - 1 && !visited[i + 1] && (lum[i + 1].toInt() and 255) > thr) { visited[i + 1] = true; queue[tail++] = i + 1 }
                if (y > 0 && !visited[i - lw] && (lum[i - lw].toInt() and 255) > thr) { visited[i - lw] = true; queue[tail++] = i - lw }
                if (y < lh - 1 && !visited[i + lw] && (lum[i + lw].toInt() and 255) > thr) { visited[i + lw] = true; queue[tail++] = i + lw }
            }
            if (tail > bestSize) { bestSize = tail; cTL = tl; cTR = tr; cBR = br; cBL = bl }
        }

        val tlx: Float
        val tly: Float
        val trx: Float
        val try0: Float
        val brx: Float
        val bry: Float
        val blx: Float
        val bly: Float
        if (bestSize < ln * 12 / 100) {
            tlx = 0f; tly = 0f
            trx = (w - 1).toFloat(); try0 = 0f
            brx = (w - 1).toFloat(); bry = (h - 1).toFloat()
            blx = 0f; bly = (h - 1).toFloat()
        } else {
            tlx = ((cTL % lw) * s).toFloat(); tly = ((cTL / lw) * s).toFloat()
            trx = ((cTR % lw) * s).toFloat(); try0 = ((cTR / lw) * s).toFloat()
            brx = ((cBR % lw) * s).toFloat(); bry = ((cBR / lw) * s).toFloat()
            blx = ((cBL % lw) * s).toFloat(); bly = ((cBL / lw) * s).toFloat()
        }

        var ow = max(hypot((trx - tlx).toDouble(), (try0 - tly).toDouble()), hypot((brx - blx).toDouble(), (bry - bly).toDouble())).toInt()
        var oh = max(hypot((blx - tlx).toDouble(), (bly - tly).toDouble()), hypot((brx - trx).toDouble(), (bry - try0).toDouble())).toInt()
        val longest = max(ow, oh)
        if (longest > 3600) {
            ow = ow * 3600 / longest
            oh = oh * 3600 / longest
        }
        ow = max(ow, 64)
        oh = max(oh, 64)

        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val v = oy / (oh - 1f)
            for (ox in 0 until ow) {
                val u = ox / (ow - 1f)
                val sx = (1f - v) * ((1f - u) * tlx + u * trx) + v * ((1f - u) * blx + u * brx)
                val sy = (1f - v) * ((1f - u) * tly + u * try0) + v * ((1f - u) * bly + u * bry)
                out[oy * ow + ox] = bilinear(src, w, h, sx, sy)
            }
        }

        // flatten uneven lighting using a heavily blurred copy as the "paper colour"
        val ds = 8
        val sw = max(ow / ds, 1)
        val sh = max(oh / ds, 1)
        val small = IntArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                small[y * sw + x] = out[min(y * ds + ds / 2, oh - 1) * ow + min(x * ds + ds / 2, ow - 1)]
            }
        }
        val blurred = boxBlurArgb(boxBlurArgb(small, sw, sh, 3), sw, sh, 3)
        for (oy in 0 until oh) {
            val by = min(oy / ds, sh - 1) * sw
            for (ox in 0 until ow) {
                val p = out[oy * ow + ox]
                val q = blurred[by + min(ox / ds, sw - 1)]
                var r = (((p shr 16) and 255) * 235) / max((q shr 16) and 255, 60)
                var g = (((p shr 8) and 255) * 235) / max((q shr 8) and 255, 60)
                var b = ((p and 255) * 235) / max(q and 255, 60)
                r = (((min(r, 255) - 25) * 255) / 205).coerceIn(0, 255)
                g = (((min(g, 255) - 25) * 255) / 205).coerceIn(0, 255)
                b = (((min(b, 255) - 25) * 255) / 205).coerceIn(0, 255)
                if (bw) {
                    val l = (r * 77 + g * 150 + b * 29) shr 8
                    val gray = (smooth((l - 110) / 65f) * 255f).toInt()
                    out[oy * ow + ox] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                } else {
                    out[oy * ow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Rotated(out, ow, oh)
    }
}
