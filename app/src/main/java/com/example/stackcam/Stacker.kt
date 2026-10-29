package com.example.stackcam

import android.media.Image
import kotlin.math.max

/** YUV_420_888 -> packed ARGB (BT.601 full range). */
object Yuv {
    fun toArgb(img: Image, out: IntArray) {
        val w = img.width
        val h = img.height
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        val yb = ByteArray(yp.buffer.remaining()).also { yp.buffer.get(it) }
        val ub = ByteArray(up.buffer.remaining()).also { up.buffer.get(it) }
        val vb = ByteArray(vp.buffer.remaining()).also { vp.buffer.get(it) }
        val yRow = yp.rowStride
        val uRow = up.rowStride
        val uPix = up.pixelStride
        val vRow = vp.rowStride
        val vPix = vp.pixelStride
        for (j in 0 until h) {
            val yo = j * yRow
            val uo = (j shr 1) * uRow
            val vo = (j shr 1) * vRow
            var o = j * w
            for (i in 0 until w) {
                val y = yb[yo + i].toInt() and 0xFF
                val u = (ub[uo + (i shr 1) * uPix].toInt() and 0xFF) - 128
                val v = (vb[vo + (i shr 1) * vPix].toInt() and 0xFF) - 128
                var r = y + ((91881 * v) shr 16)
                var g = y - ((22554 * u + 46802 * v) shr 16)
                var b = y + ((116130 * u) shr 16)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}

/** Mean of all frames: silky water, smooth clouds, crowd blur. */
class AverageStacker(private val n: Int) {
    private val sum = IntArray(n * 3)
    var frames = 0
        private set

    fun add(src: IntArray) {
        for (i in 0 until n) {
            val p = src[i]
            val k = i * 3
            sum[k] += (p shr 16) and 255
            sum[k + 1] += (p shr 8) and 255
            sum[k + 2] += p and 255
        }
        frames++
    }

    fun result(): IntArray {
        val out = IntArray(n)
        val f = max(frames, 1)
        for (i in 0 until n) {
            val k = i * 3
            out[i] = (0xFF shl 24) or ((sum[k] / f) shl 16) or ((sum[k + 1] / f) shl 8) or (sum[k + 2] / f)
        }
        return out
    }
}

/** Per-channel maximum of all frames: light trails, star trails, light painting. */
class LightenStacker(private val n: Int) {
    val mx = IntArray(n) { 0xFF000000.toInt() }

    fun add(src: IntArray) {
        for (i in 0 until n) {
            val a = mx[i]
            val b = src[i]
            val r = max((a shr 16) and 255, (b shr 16) and 255)
            val g = max((a shr 8) and 255, (b shr 8) and 255)
            val bl = max(a and 255, b and 255)
            mx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
}
