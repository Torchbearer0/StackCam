package com.example.stackcam

import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun mixArgb(p: Int, q: Int, t: Float): Int {
    val ti = (t * 256f).toInt().coerceIn(0, 256)
    val r = ((((p shr 16) and 255) * (256 - ti)) + (((q shr 16) and 255) * ti)) shr 8
    val g = ((((p shr 8) and 255) * (256 - ti)) + (((q shr 8) and 255) * ti)) shr 8
    val b = (((p and 255) * (256 - ti)) + ((q and 255) * ti)) shr 8
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** Copies the three planes of a YUV_420_888 image into tight arrays (Y: w*h, U/V: (w/2)*(h/2)). */
fun readPlanes(img: Image, y: ByteArray, u: ByteArray, v: ByteArray) {
    val w = img.width
    val h = img.height
    val yp = img.planes[0]
    val up = img.planes[1]
    val vp = img.planes[2]
    val yb = yp.buffer
    for (row in 0 until h) {
        yb.position(row * yp.rowStride)
        yb.get(y, row * w, w)
    }
    val cw = w / 2
    val chh = h / 2
    val ub = up.buffer
    val vb = vp.buffer
    val ur = up.rowStride
    val upx = up.pixelStride
    val vr = vp.rowStride
    val vpx = vp.pixelStride
    for (row in 0 until chh) {
        val uo = row * ur
        val vo = row * vr
        val d = row * cw
        for (col in 0 until cw) {
            u[d + col] = ub.get(uo + col * upx)
            v[d + col] = vb.get(vo + col * vpx)
        }
    }
}

/** A stored camera frame in YUV (18 MB at 12 MP instead of 48 MB as ARGB). */
class YuvFrame(val w: Int, val h: Int, val yp: ByteArray, val up: ByteArray, val vp: ByteArray) {

    /** Pixel as ARGB with per-channel gain (256 = 1.0). Coordinates are clamped. */
    fun argbG(x: Int, y: Int, gr: Int, gg: Int, gb: Int): Int {
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        val yv = yp[cy * w + cx].toInt() and 255
        val ci = (cy shr 1) * (w shr 1) + (cx shr 1)
        val u = (up[ci].toInt() and 255) - 128
        val v = (vp[ci].toInt() and 255) - 128
        var r = yv + ((91881 * v) shr 16)
        var g = yv - ((22554 * u + 46802 * v) shr 16)
        var b = yv + ((116130 * u) shr 16)
        r = (r * gr) shr 8
        g = (g * gg) shr 8
        b = (b * gb) shr 8
        if (r < 0) r = 0 else if (r > 255) r = 255
        if (g < 0) g = 0 else if (g > 255) g = 255
        if (b < 0) b = 0 else if (b > 255) b = 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        fun from(img: Image): YuvFrame {
            val w = img.width
            val h = img.height
            val y = ByteArray(w * h)
            val u = ByteArray((w / 2) * (h / 2))
            val v = ByteArray((w / 2) * (h / 2))
            readPlanes(img, y, u, v)
            return YuvFrame(w, h, y, u, v)
        }
    }
}

object Align {
    /** Low-resolution luminance of an ARGB image (one sample per s x s block). */
    fun lumLow(argb: IntArray, w: Int, h: Int, s: Int): ByteArray {
        val lw = w / s
        val lh = h / s
        val out = ByteArray(lw * lh)
        for (y in 0 until lh) {
            val yy = y * s
            for (x in 0 until lw) {
                val p = argb[yy * w + x * s]
                out[y * lw + x] = ((((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8).toByte()
            }
        }
        return out
    }

    fun lumOfArgb(low: IntArray): ByteArray {
        val out = ByteArray(low.size)
        for (i in low.indices) {
            val p = low[i]
            out[i] = ((((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8).toByte()
        }
        return out
    }

    /** Rough sharpness score of a low-res luminance image. */
    fun sharp(lum: ByteArray, lw: Int, lh: Int): Long {
        var s = 0L
        var y = 0
        while (y < lh) {
            val ro = y * lw
            var x = 0
            while (x < lw - 1) {
                s += abs((lum[ro + x].toInt() and 255) - (lum[ro + x + 1].toInt() and 255)).toLong()
                x += 2
            }
            y += 2
        }
        return s
    }

    /**
     * Finds (dx, dy) so that cur(x + dx, y + dy) matches ref(x, y), searching around (cx, cy)
     * within +-rx / +-ry. Uses a truncated, overlap-normalised sum of absolute differences.
     */
    fun shift(ref: ByteArray, cur: ByteArray, lw: Int, lh: Int, rx: Int, ry: Int, cx: Int, cy: Int, stride: Int): IntArray {
        var best = Long.MAX_VALUE
        var bdx = cx
        var bdy = cy
        for (dy in cy - ry..cy + ry) {
            for (dx in cx - rx..cx + rx) {
                var sad = 0L
                var n = 0
                var y = max(0, -dy)
                val yEnd = min(lh, lh - dy)
                while (y < yEnd) {
                    var x = max(0, -dx)
                    val xEnd = min(lw, lw - dx)
                    val ro = y * lw
                    val co = (y + dy) * lw + dx
                    while (x < xEnd) {
                        var d = (ref[ro + x].toInt() and 255) - (cur[co + x].toInt() and 255)
                        if (d < 0) d = -d
                        if (d > 40) d = 40
                        sad += d
                        n++
                        x += stride
                    }
                    y += stride
                }
                if (n < 64) continue
                val score = sad * 1000L / n
                if (score < best) {
                    best = score
                    bdx = dx
                    bdy = dy
                }
            }
        }
        return intArrayOf(bdx, bdy)
    }
}

/** Accumulates YUV frames (optionally shifted) with very little work per frame. */
class YuvAccum(private val w: Int, private val h: Int) {
    private val cw = w / 2
    private val chh = h / 2
    private val sumY = IntArray(w * h)
    private val sumU = IntArray(cw * chh)
    private val sumV = IntArray(cw * chh)
    private val cnt = ShortArray(w * h)
    private val ty = ByteArray(w * h)
    private val tu = ByteArray(cw * chh)
    private val tv = ByteArray(cw * chh)
    var frames = 0
        private set

    fun load(img: Image) = readPlanes(img, ty, tu, tv)

    fun lowLum(s: Int): ByteArray {
        val lw = w / s
        val lh = h / s
        val out = ByteArray(lw * lh)
        for (y in 0 until lh) {
            val yy = y * s
            for (x in 0 until lw) {
                val i = yy * w + x * s
                val a = (ty[i].toInt() and 255) + (ty[i + 1].toInt() and 255) + (ty[i + w].toInt() and 255) + (ty[i + w + 1].toInt() and 255)
                out[y * lw + x] = (a shr 2).toByte()
            }
        }
        return out
    }

    /** Adds the loaded frame; dx, dy must be even and mean: reference (x, y) is read from frame (x + dx, y + dy). */
    fun addLoaded(dx: Int, dy: Int) {
        for (y in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= h) continue
            val ro = y * w
            val so = sy * w + dx
            val x0 = max(0, -dx)
            val x1 = min(w, w - dx)
            for (x in x0 until x1) {
                sumY[ro + x] += ty[so + x].toInt() and 255
                cnt[ro + x] = (cnt[ro + x] + 1).toShort()
            }
        }
        val cdx = dx / 2
        val cdy = dy / 2
        for (y in 0 until chh) {
            val sy = y + cdy
            if (sy < 0 || sy >= chh) continue
            val ro = y * cw
            val so = sy * cw + cdx
            val x0 = max(0, -cdx)
            val x1 = min(cw, cw - cdx)
            for (x in x0 until x1) {
                sumU[ro + x] += tu[so + x].toInt() and 255
                sumV[ro + x] += tv[so + x].toInt() and 255
            }
        }
        frames++
    }

    fun result(lut: IntArray?): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ro = y * w
            val cro = (y shr 1) * cw
            val yb = (y and 1.inv()) * w
            for (x in 0 until w) {
                val c = max(cnt[ro + x].toInt(), 1)
                val yv = sumY[ro + x] / c
                val ci = cro + (x shr 1)
                val cc = max(cnt[yb + (x and 1.inv())].toInt(), 1)
                val u = (sumU[ci] / cc).coerceIn(0, 255) - 128
                val v = (sumV[ci] / cc).coerceIn(0, 255) - 128
                var r = yv + ((91881 * v) shr 16)
                var g = yv - ((22554 * u + 46802 * v) shr 16)
                var b = yv + ((116130 * u) shr 16)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                if (lut != null) {
                    r = lut[r]
                    g = lut[g]
                    b = lut[b]
                }
                out[ro + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
