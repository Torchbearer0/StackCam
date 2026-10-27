package com.example.stackcam

import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

/**
 * Clone mode: take a few shots from a fixed position while the subject moves.
 * The background is the per-pixel median of all shots; each shot's moving subject
 * (difference from background) is then pasted into one frame.
 */
object Clone {
    private const val THRESH = 28

    fun composite(frames: List<IntArray>, w: Int, h: Int): IntArray {
        val n = w * h
        val k = frames.size
        val bg = IntArray(n)
        val tmp = IntArray(k)
        for (i in 0 until n) {
            var packed = 0xFF shl 24
            for (c in 0 until 3) {
                val sh = 16 - 8 * c
                for (f in 0 until k) tmp[f] = (frames[f][i] shr sh) and 255
                java.util.Arrays.sort(tmp)
                packed = packed or (tmp[k / 2] shl sh)
            }
            bg[i] = packed
        }
        val out = bg.copyOf()
        val mask = ByteArray(n)
        for (f in 0 until k) {
            val fr = frames[f]
            for (i in 0 until n) {
                val a = fr[i]
                val b = bg[i]
                val d = max(
                    abs(((a shr 16) and 255) - ((b shr 16) and 255)),
                    max(abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))
                )
                mask[i] = if (d > THRESH) 0xFF.toByte() else 0
            }
            var soft = boxBlur(mask, w, h, 6)
            for (i in 0 until n) {
                val v = soft[i].toInt() and 255
                soft[i] = (((v - 50) * 255) / 100).coerceIn(0, 255).toByte()
            }
            soft = boxBlur(soft, w, h, 2)
            for (i in 0 until n) {
                val al = soft[i].toInt() and 255
                if (al == 0) continue
                val ia = 255 - al
                val o = out[i]
                val s = fr[i]
                val r = (((s shr 16) and 255) * al + ((o shr 16) and 255) * ia) / 255
                val g = (((s shr 8) and 255) * al + ((o shr 8) and 255) * ia) / 255
                val bl = ((s and 255) * al + (o and 255) * ia) / 255
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        return out
    }

    private fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(src.size)
        val out = ByteArray(src.size)
        val win = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)].toInt() and 255
            for (x in 0 until w) {
                tmp[row + x] = (sum / win).toByte()
                val add = min(x + r + 1, w - 1)
                val rem = max(x - r, 0)
                sum += (src[row + add].toInt() and 255) - (src[row + rem].toInt() and 255)
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x].toInt() and 255
            for (y in 0 until h) {
                out[y * w + x] = (sum / win).toByte()
                val add = min(y + r + 1, h - 1)
                val rem = max(y - r, 0)
                sum += (tmp[add * w + x].toInt() and 255) - (tmp[rem * w + x].toInt() and 255)
            }
        }
        return out
    }
}
