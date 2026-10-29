package com.example.stackcam

import kotlin.math.abs
import kotlin.math.max

/**
 * Clone mode v2. Frames are aligned and brightness-matched, the background is the per-pixel median,
 * and each shot's moving subject is found on a low-resolution difference map that is cleaned with
 * opening/closing, so refocus blur and noise in the background do not become part of the subject.
 * Background pixels come from whichever frame shows background at that spot.
 */
object CloneV2 {
    private const val S = 4

    private fun lowRes(f: YuvFrame): IntArray {
        val lw = f.w / S
        val lh = f.h / S
        val out = IntArray(lw * lh)
        for (y in 0 until lh) {
            for (x in 0 until lw) {
                val bx = x * S
                val by = y * S
                val p0 = f.argbG(bx + 1, by + 1, 256, 256, 256)
                val p1 = f.argbG(bx + 3, by + 1, 256, 256, 256)
                val p2 = f.argbG(bx + 1, by + 3, 256, 256, 256)
                val p3 = f.argbG(bx + 3, by + 3, 256, 256, 256)
                val r = (((p0 shr 16) and 255) + ((p1 shr 16) and 255) + ((p2 shr 16) and 255) + ((p3 shr 16) and 255)) shr 2
                val g = (((p0 shr 8) and 255) + ((p1 shr 8) and 255) + ((p2 shr 8) and 255) + ((p3 shr 8) and 255)) shr 2
                val b = ((p0 and 255) + (p1 and 255) + (p2 and 255) + (p3 and 255)) shr 2
                out[y * lw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun thr(a: FloatArray, t: Float): FloatArray {
        val o = FloatArray(a.size)
        for (i in a.indices) o[i] = if (a[i] > t) 1f else 0f
        return o
    }

    private fun channelMean(a: IntArray, shift: Int): Double {
        var sum = 0L
        for (v in a) sum += (v shr shift) and 255
        return sum.toDouble() / a.size
    }

    fun composite(frames: List<YuvFrame>, w: Int, h: Int): IntArray {
        val k = frames.size
        val lw = w / S
        val lh = h / S
        val ln = lw * lh

        val low0 = Array(k) { lowRes(frames[it]) }
        val lums = Array(k) { Align.lumOfArgb(low0[it]) }

        // 1. alignment against frame 0 (low-res units)
        val dxs = IntArray(k)
        val dys = IntArray(k)
        for (f in 1 until k) {
            val sh = Align.shift(lums[0], lums[f], lw, lh, 8, 8, 0, 0, 2)
            dxs[f] = sh[0]
            dys[f] = sh[1]
        }
        val al = Array(k) { f ->
            val o = IntArray(ln)
            for (y in 0 until lh) {
                val sy = (y + dys[f]).coerceIn(0, lh - 1)
                for (x in 0 until lw) {
                    val sx = (x + dxs[f]).coerceIn(0, lw - 1)
                    o[y * lw + x] = low0[f][sy * lw + sx]
                }
            }
            o
        }

        // 2. brightness / colour matching to frame 0
        val gain = Array(k) { IntArray(3) { 256 } }
        val ref = DoubleArray(3)
        for (c in 0 until 3) ref[c] = channelMean(al[0], 16 - 8 * c)
        for (f in 1 until k) {
            for (c in 0 until 3) {
                val m = channelMean(al[f], 16 - 8 * c)
                val g = (ref[c] / max(m, 1.0)).coerceIn(0.85, 1.18)
                gain[f][c] = (g * 256).toInt()
            }
            for (i in 0 until ln) {
                val p = al[f][i]
                val r = ((((p shr 16) and 255) * gain[f][0]) shr 8).coerceAtMost(255)
                val g = ((((p shr 8) and 255) * gain[f][1]) shr 8).coerceAtMost(255)
                val b = (((p and 255) * gain[f][2]) shr 8).coerceAtMost(255)
                al[f][i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // 3. median background (low-res)
        val bg = IntArray(ln)
        val tmp = IntArray(k)
        for (i in 0 until ln) {
            var packed = 0xFF shl 24
            for (c in 0 until 3) {
                val sh = 16 - 8 * c
                for (f in 0 until k) tmp[f] = (al[f][i] shr sh) and 255
                java.util.Arrays.sort(tmp)
                packed = packed or (tmp[k / 2] shl sh)
            }
            bg[i] = packed
        }

        // 4. subject masks (low-res) with noise-adaptive threshold, opening and closing
        val alpha = Array(k) { f ->
            val d = ByteArray(ln)
            val hist = IntArray(256)
            for (i in 0 until ln) {
                val a = al[f][i]
                val b = bg[i]
                val dd = max(
                    abs(((a shr 16) and 255) - ((b shr 16) and 255)),
                    max(abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))
                ).coerceAtMost(255)
                d[i] = dd.toByte()
                hist[dd]++
            }
            var acc = 0
            var med = 0
            for (v in 0 until 256) {
                acc += hist[v]
                if (acc >= ln / 2) { med = v; break }
            }
            val t = max(18, 3 * med)
            val m0 = FloatArray(ln) { if ((d[it].toInt() and 255) > t) 1f else 0f }
            val e0 = thr(boxBlurF(m0, lw, lh, 2), 0.55f)
            val o0 = thr(boxBlurF(e0, lw, lh, 2), 0.12f)
            val c1 = thr(boxBlurF(o0, lw, lh, 7), 0.05f)
            val c2 = thr(boxBlurF(c1, lw, lh, 7), 0.95f)
            boxBlurF(c2, lw, lh, 2)
        }

        // 5. full-resolution composition
        val out = IntArray(w * h)
        val lx0 = IntArray(w)
        val lx1 = IntArray(w)
        val lxw = FloatArray(w)
        for (x in 0 until w) {
            val fx = (x + 0.5f) / S - 0.5f
            val i0 = Math.floor(fx.toDouble()).toInt()
            lxw[x] = fx - i0
            lx0[x] = i0.coerceIn(0, lw - 1)
            lx1[x] = (i0 + 1).coerceIn(0, lw - 1)
        }
        val al2 = FloatArray(k)
        for (y in 0 until h) {
            val fy = (y + 0.5f) / S - 0.5f
            val j0 = Math.floor(fy.toDouble()).toInt()
            val wy = fy - j0
            val r0 = j0.coerceIn(0, lh - 1) * lw
            val r1 = (j0 + 1).coerceIn(0, lh - 1) * lw
            var prev = 0
            for (x in 0 until w) {
                val a0 = lx0[x]
                val a1 = lx1[x]
                val wx = lxw[x]
                var best = 0
                var bestA = 2f
                for (f in 0 until k) {
                    val arr = alpha[f]
                    val top = arr[r0 + a0] * (1f - wx) + arr[r0 + a1] * wx
                    val bot = arr[r1 + a0] * (1f - wx) + arr[r1 + a1] * wx
                    val a = top * (1f - wy) + bot * wy
                    al2[f] = a
                    if (a < bestA) { bestA = a; best = f }
                }
                if (al2[prev] <= bestA + 0.02f) best = prev
                prev = best
                var px = frames[best].argbG(x + dxs[best] * S, y + dys[best] * S, gain[best][0], gain[best][1], gain[best][2])
                for (f in 0 until k) {
                    val a = al2[f]
                    if (a > 0.004f && f != best) {
                        val q = frames[f].argbG(x + dxs[f] * S, y + dys[f] * S, gain[f][0], gain[f][1], gain[f][2])
                        px = mixArgb(px, q, a)
                    }
                }
                out[y * w + x] = px
            }
        }
        return out
    }
}
